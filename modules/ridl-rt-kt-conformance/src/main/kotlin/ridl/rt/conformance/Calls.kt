// Calls: delivery, settlement, the caller's sequence number, `forget`, and
// which handler a claim belongs to (`Caller` and `Handler`).
// `crates/ridl-rt-conformance/src/calls.rs`.
//
// Every handler here calls `serve` for the members it takes claims on before
// it takes one, because `Handler.serve` is what starts presentation. The suite
// has no test of a handler that has served nothing.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.InterfaceNo
import ridl.rt.error.Contract as ContractError
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ReadError
import ridl.rt.port.SettleError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import java.nio.ByteBuffer
import kotlin.reflect.KFunction0

/** The tests of `Caller` and `Handler`. */
public class CallsContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a command is delivered and acknowledged`,
        ::`a query is delivered and replied`,
        ::`settle can be made to fail once then succeed`,
        ::`two callers on one provider are two claims under one seq`,
        ::`a caller sequence number counts that caller calls`,
        ::`a settled outcome reports the contract error the provider settled`,
        ::`a claim is presented once and settled once`,
        ::`a short buffer leaves the claim for the next call`,
        ::`forget releases a settled correlation`,
        ::`forget before the claim is presented leaves the call for the provider`,
        ::`forget between the claim and the settlement leaves the settlement valid`,
        ::`a claim that was never presented cannot be settled`,
        ::`an injected settle failure is not spent on an unknown claim`,
        ::`a handler cannot settle another handlers claim`,
        ::`two handlers each receive only what they served`,
    )

    private fun ok(vararg values: Int): Result<ByteBuffer> = Result.success(bytes(*values))

    private fun Handler.claim(buf: ByteBuffer = out(8)): Claim = checkNotNull(nextClaim(buf)) { "a claim is waiting" }

    /** A settlement that fails with anything but `UnknownClaim`: the injected fault. */
    private fun assertInjectedFailure(settle: () -> Unit, message: String) {
        val error = assertThrows<SettleError>(message) { settle() }
        assertNotEquals(SettleError.UnknownClaim, error, message)
    }

    /** A command reaches the handler with its arguments, and the settlement is observable through `ack`. */
    public fun `a command is delivered and acknowledged`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1, 2, 3))
        assertNull(rt.ack(correlation), "not yet settled")

        val buf = out(8)
        val claim = rt.claim(buf)
        assertEquals(IFACE, claim.iface)
        assertEquals(ORD, claim.ord)
        assertArrayEquals(array(1, 2, 3), buf.written())

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation), "settlement is observable through ack")
    }

    /**
     * A query reaches the handler, and the reply bytes it settles are read
     * back through `reply`. `ack` answers `null` for a query's correlation.
     */
    public fun `a query is delivered and replied`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.query(IFACE, ORD, bytes(9))

        val buf = out(8)
        val claim = rt.claim(buf)
        assertArrayEquals(array(9), buf.written())

        rt.settle(claim.id, ok(7, 7))

        val reply = out(8)
        assertEquals(Result.success(2), rt.reply(correlation, reply))
        assertArrayEquals(array(7, 7), reply.written())
        // A query's correlation always answers `null` from `ack`.
        assertNull(rt.ack(correlation))
    }

    /**
     * A settlement that fails records no outcome, and the claim can still be
     * settled. [Factory.failNextSettle] is the injected failure that makes the
     * generated `dispatch`'s count of accepted settlements reachable.
     */
    public fun `settle can be made to fail once then succeed`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        val claim = rt.claim()

        factory.failNextSettle(rt)
        assertInjectedFailure({ rt.settle(claim.id, ok()) }, "the injected failure surfaces from settle as an error other than `UnknownClaim`")
        assertNull(rt.ack(correlation), "a failed settle records no outcome")

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * driftsys/ridl#308, and the rule ADR-0021 decision 5 fixes over it: a
     * caller's sequence number is unique per caller, not per channel, so two
     * callers on their first call both carry seq 1 — and they are still two
     * claims, never merged.
     */
    public fun `two callers on one provider are two claims under one seq`() {
        val rt = runtime()
        val second = factory.caller(rt)
        rt.serve(IFACE, listOf(ORD))

        val a = rt.command(IFACE, ORD, bytes(1))
        val b = second.command(IFACE, ORD, bytes(2))
        assertNotEquals(a, b, "the correlations are distinct")

        var buf = out(8)
        val firstClaim = rt.claim(buf)
        assertArrayEquals(array(1), buf.written())
        buf = out(8)
        val secondClaim = rt.claim(buf)
        assertArrayEquals(array(2), buf.written())
        assertEquals(1uL, firstClaim.envelope.seq)
        assertEquals(1uL, secondClaim.envelope.seq, "both callers are on their first call, so both carry seq 1")
        assertNotEquals(firstClaim.id, secondClaim.id, "and they are two claims, not one")

        rt.settle(firstClaim.id, ok())
        rt.settle(secondClaim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(a))
        assertEquals(Result.success(Unit), second.ack(b))
    }

    /** A caller's sequence number counts that caller's calls, commands and queries on one counter. */
    public fun `a caller sequence number counts that caller calls`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1))
        rt.query(IFACE, ORD, bytes(2))

        assertEquals(1uL, rt.claim().envelope.seq)
        assertEquals(2uL, rt.claim().envelope.seq, "a command and a query share one counter")
    }

    /** A contract error the provider settles is the outcome the caller sees. */
    public fun `a settled outcome reports the contract error the provider settled`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        rt.settle(rt.claim().id, Result.failure(ContractError.PreconditionFailed))
        assertEquals(Result.failure<Unit>(ContractError.PreconditionFailed), rt.ack(correlation))
    }

    /** `nextClaim` presents a call once, and a claim already settled is unknown to a second settlement. */
    public fun `a claim is presented once and settled once`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1))
        val claim = rt.claim()
        assertNull(rt.nextClaim(out(8)), "the claim is presented once")

        rt.settle(claim.id, ok())
        assertThrows<SettleError.UnknownClaim>("a claim already settled is unknown to a second settlement") {
            rt.settle(claim.id, ok())
        }
    }

    /** `ReadError.Short` does not consume the call. */
    public fun `a short buffer leaves the claim for the next call`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1, 2, 3))

        assertEquals(ReadError.Short(3), assertThrows<ReadError.Short> { rt.nextClaim(out(1)) })

        val buf = out(8)
        rt.claim(buf)
        assertArrayEquals(array(1, 2, 3), buf.written())
    }

    /** After `forget`, a settled outcome is no longer retrievable. */
    public fun `forget releases a settled correlation`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        rt.settle(rt.claim().id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))

        rt.forget(correlation)
        assertNull(rt.ack(correlation), "the outcome is no longer retrievable")
    }

    /**
     * `Caller.forget` releases the caller's interest in an outcome. It is not
     * a cancellation: `Handler`'s contract is that every claim is settled, and
     * a call already sent is the provider's.
     */
    public fun `forget before the claim is presented leaves the call for the provider`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        rt.forget(correlation)

        val buf = out(8)
        val claim = rt.claim(buf)
        assertArrayEquals(array(1), buf.written(), "the call is still presented")
        rt.settle(claim.id, ok())
        assertNull(rt.ack(correlation), "but the caller asked not to be told")
    }

    /** A `forget` between the claim and the settlement does not revoke the provider's settlement, and the caller is not told of it. */
    public fun `forget between the claim and the settlement leaves the settlement valid`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.query(IFACE, ORD, bytes(1))
        val claim = rt.claim()
        rt.forget(correlation)

        rt.settle(claim.id, ok(7))
        assertNull(rt.reply(correlation, out(8)))
    }

    /**
     * A correlation is not a claim. Before this call is presented there is no
     * claim to settle, and a settlement accepted here would acknowledge a call
     * the provider has not seen.
     */
    public fun `a claim that was never presented cannot be settled`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        assertThrows<SettleError.UnknownClaim> { rt.settle(ClaimId(correlation.value), ok()) }
        assertNull(rt.ack(correlation), "and nothing was acknowledged")

        rt.settle(rt.claim().id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * A settlement of a claim the handler does not hold is checked before the
     * injected failure is consumed, so the failure still has the next real
     * settlement to fail. The claim the handler does not hold is one it has
     * already settled, which no runtime can hold again.
     */
    public fun `an injected settle failure is not spent on an unknown claim`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1))
        rt.command(IFACE, ORD, bytes(2))
        val settled = rt.claim()
        val claim = rt.claim()
        rt.settle(settled.id, ok())

        factory.failNextSettle(rt)
        assertThrows<SettleError.UnknownClaim>("the claim is checked before the injected failure is consumed") {
            rt.settle(settled.id, ok())
        }
        assertInjectedFailure({ rt.settle(claim.id, ok()) }, "so the injected failure still has the next real settlement to fail")
    }

    /** Two providers in one process settle their own calls and not each other's: a claim belongs to the handler it was presented to. */
    public fun `a handler cannot settle another handlers claim`() {
        val rt = runtime()
        val second = factory.handler(rt)
        rt.serve(IFACE, listOf(ORD))
        second.serve(InterfaceNo(2u), listOf(ORD))

        val correlation = rt.command(IFACE, ORD, bytes(1))
        val claim = rt.claim()

        assertThrows<SettleError.UnknownClaim>("the claim is not the second handler's to settle") {
            second.settle(claim.id, ok())
        }
        assertNull(rt.ack(correlation), "and nothing was acknowledged in the caller's name")

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * Two components providing different interfaces in one process: each
     * handler is presented only the calls to what it served. A handler
     * presented another handler's call would settle it `UnknownInteraction`
     * through the generated dispatch, and the call would be lost.
     */
    public fun `two handlers each receive only what they served`() {
        val rt = runtime()
        val second = factory.handler(rt)
        rt.serve(IFACE, listOf(ORD))
        second.serve(InterfaceNo(2u), listOf(ORD))

        rt.command(InterfaceNo(2u), ORD, bytes(7))
        rt.command(IFACE, ORD, bytes(8))

        var buf = out(8)
        assertEquals(IFACE, rt.claim(buf).iface, "the call the first handler served")
        assertArrayEquals(array(8), buf.written())

        buf = out(8)
        assertEquals(InterfaceNo(2u), second.claim(buf).iface, "and the second handler's own")
        assertArrayEquals(array(7), buf.written())

        assertNull(rt.nextClaim(out(8)), "neither handler consumed the other's call")
        assertNull(second.nextClaim(out(8)))
    }
}
