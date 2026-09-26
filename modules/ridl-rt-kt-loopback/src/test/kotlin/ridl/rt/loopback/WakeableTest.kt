package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.Transport
import ridl.rt.port.Interest
import ridl.rt.sample.Duration
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `Wakeable` contract over the loopback, and `Transport.Busy` carried back
 * to a caller: the tests story E11.16 added to
 * `crates/ridl-loopback/tests/ports.rs` on ridl `main` (c0fa57c), ahead of the
 * release that carries them, under the same names.
 *
 * `Wakeable` is on the three handles a task waits on, each for the keys of its
 * own role: the source for `Event`, the caller for `Outcome` and `Slot`, the
 * handler for `Claim`.
 */
class WakeableTest {
    private val iface = InterfaceNo(1u)
    private val ord = Ordinal(1u)

    private fun runtime() = Loopback(CatalogRef("face.demo", CatalogHash(ByteArray(32))))

    /** A waker that counts its wakes. */
    private class Counter : Waker {
        val wakes = AtomicInteger()

        override fun wake() {
            wakes.incrementAndGet()
        }
    }

    private fun out() = ByteBuffer.allocate(8)

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

    @Test
    fun `a waiter on an outcome is woken exactly once by its settlement`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()

        val c = caller.command(iface, ord, bytes(1))
        val count = Counter()
        caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes.get(), "nothing is known about the call yet")

        val claim = handler.nextClaim(out())!!
        assertEquals(0, count.wakes.get(), "a presented claim is not an outcome")

        handler.settle(claim.id, Result.success(bytes()))
        assertEquals(1, count.wakes.get(), "the settlement wakes the waiter once")
        assertEquals(Result.success(Unit), caller.ack(c), "and the outcome is readable")

        caller.command(iface, ord, bytes(2))
        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes()))
        assertEquals(1, count.wakes.get(), "a woken waker is not woken again")

        val later = Counter()
        caller.wakeOn(Interest.Outcome(c), later)
        assertEquals(1, later.wakes.get(), "the outcome is known, so at once")
        assertEquals(1, count.wakes.get(), "the settlement cleared the stored waker")
    }

    @Test
    fun `each settlement wakes only its own calls waiter`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val first = caller.command(iface, ord, bytes(1))
        val second = caller.query(iface, ord, bytes(2))
        val one = Counter()
        val two = Counter()
        caller.wakeOn(Interest.Outcome(first), one)
        caller.wakeOn(Interest.Outcome(second), two)

        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes()))
        assertEquals(1 to 0, one.wakes.get() to two.wakes.get(), "the command's settlement wakes the command's waiter")
        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes(9)))
        assertEquals(1 to 1, one.wakes.get() to two.wakes.get(), "the query's wakes the query's")
    }

    @Test
    fun `a second registration under a key wakes the displaced waker`() {
        val rt = runtime()
        val caller = rt.caller()
        val c = caller.command(iface, ord, bytes(1))
        val first = Counter()
        val second = Counter()
        caller.wakeOn(Interest.Outcome(c), first)
        caller.wakeOn(Interest.Outcome(c), second)
        assertEquals(1, first.wakes.get(), "the displaced waker is woken, so no task waits on it")
        assertEquals(0, second.wakes.get())

        val handler = rt.handler()
        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes()))
        assertEquals(1 to 1, first.wakes.get() to second.wakes.get(), "the settlement wakes the stored one only")
    }

    @Test
    fun `registering the same task again wakes nothing`() {
        val rt = runtime()
        val caller = rt.caller()
        val c = caller.command(iface, ord, bytes(1))
        val count = Counter()
        caller.wakeOn(Interest.Outcome(c), count)
        caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes.get(), "the same waker under the same key displaces nothing")

        val handler = rt.handler()
        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes()))
        assertEquals(1, count.wakes.get())
    }

    @Test
    fun `a forgotten calls waiter is not woken by its settlement`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val c = caller.command(iface, ord, bytes(1))
        val count = Counter()
        caller.wakeOn(Interest.Outcome(c), count)
        caller.forget(c)
        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes()))
        assertEquals(0, count.wakes.get(), "the waiter goes with the caller's interest")
        caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes.get(), "a correlation the runtime no longer holds is not stored")
    }

    @Test
    fun `a raise wakes a subscribed source and not an unsubscribed one`() {
        val rt = runtime()
        val subscribed = rt.source()
        val unsubscribed = rt.source()
        subscribed.subscribe(iface, listOf(ord))
        val yes = Counter()
        val no = Counter()
        subscribed.wakeOn(Interest.Event(iface), yes)
        unsubscribed.wakeOn(Interest.Event(iface), no)

        rt.raise(iface, ord, bytes(1))
        assertEquals(1, yes.wakes.get(), "the source that received the occurrence is woken")
        assertEquals(0, no.wakes.get(), "a source that received nothing is not")
        assertNotNull(subscribed.next(out()))
    }

    @Test
    fun `an occurrence already queued wakes its registration at once`() {
        val rt = runtime()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        rt.raise(iface, ord, bytes(1))
        val count = Counter()
        source.wakeOn(Interest.Event(iface), count)
        assertEquals(1, count.wakes.get())
        source.wakeOn(Interest.Event(InterfaceNo(2u)), Counter().also { assertEquals(0, it.wakes.get()) })
    }

    @Test
    fun `a send wakes the handler that serves the member`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(ord))
        val count = Counter()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes.get(), "no call is waiting")

        caller.command(iface, ord, bytes(1))
        assertEquals(1, count.wakes.get(), "the send wakes the serving handler")
        assertNotNull(handler.nextClaim(out()), "the woken handler finds the claim")
    }

    @Test
    fun `a call already waiting wakes the handlers registration at once`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(ord))
        caller.command(iface, ord, bytes(1))
        val count = Counter()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(1, count.wakes.get())

        val other = rt.handler()
        other.serve(iface, listOf(Ordinal(2u)))
        val none = Counter()
        other.wakeOn(Interest.Claim(iface), none)
        assertEquals(0, none.wakes.get(), "a call this handler would not be presented does not wake it at once")
    }

    @Test
    fun `a second registration under an event or claim key wakes the displaced waker`() {
        val rt = runtime()
        val source = rt.source()
        val handler = rt.handler()
        val first = Counter()
        val second = Counter()
        source.wakeOn(Interest.Event(iface), first)
        source.wakeOn(Interest.Event(iface), second)
        assertEquals(1 to 0, first.wakes.get() to second.wakes.get(), "an event key")

        val third = Counter()
        val fourth = Counter()
        handler.wakeOn(Interest.Claim(iface), third)
        handler.wakeOn(Interest.Claim(iface), fourth)
        assertEquals(1 to 0, third.wakes.get() to fourth.wakes.get(), "a claim key")

        val moved = Counter()
        handler.wakeOn(Interest.Claim(InterfaceNo(2u)), moved)
        assertEquals(1, fourth.wakes.get(), "a handle holds one claim waiter: another interface displaces it too")
    }

    @Test
    fun `the same task registering an event or claim key again wakes nothing`() {
        val rt = runtime()
        val source = rt.source()
        val handler = rt.handler()
        val count = Counter()
        source.wakeOn(Interest.Event(iface), count)
        source.wakeOn(Interest.Event(iface), count)
        handler.wakeOn(Interest.Claim(iface), count)
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes.get())
    }

    @Test
    fun `a providers busy settlement reaches the caller`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val command = caller.command(iface, ord, bytes(1))
        val query = caller.query(iface, ord, bytes(2))
        while (true) {
            val claim = handler.nextClaim(out()) ?: break
            handler.settle(claim.id, Result.failure(Transport.Busy))
        }
        assertEquals(Result.failure<Unit>(Transport.Busy), caller.ack(command))
        assertEquals(Result.failure<Int>(Transport.Busy), caller.reply(query, out()))
    }

    @Test
    fun `a closed handler leaves no waiter behind`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val count = Counter()
        handler.wakeOn(Interest.Claim(iface), count)
        handler.close()
        caller.command(iface, ord, bytes(1))
        assertEquals(0, count.wakes.get())
    }

    @Test
    fun `a slot waiter is woken at once because nothing is bounded`() {
        val count = Counter()
        runtime().caller().wakeOn(Interest.Slot, count)
        assertEquals(1, count.wakes.get(), "the loopback always has a slot free")
    }

    @Test
    fun `a key the handles role does not carry is never stored`() {
        val rt = runtime()
        val caller = rt.caller()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface, listOf(ord))
        val count = Counter()
        caller.wakeOn(Interest.Event(iface), count)
        caller.wakeOn(Interest.Claim(iface), count)
        handler.wakeOn(Interest.Event(iface), count)
        source.wakeOn(Interest.Claim(iface), count)
        source.wakeOn(Interest.Slot, count)
        rt.raise(iface, ord, bytes(1))
        caller.command(iface, ord, bytes(1))
        assertEquals(0, count.wakes.get())
    }

    @Test
    fun `the aggregate routes each key to the handle that carries it`() {
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        val event = Counter()
        val claim = Counter()
        val outcome = Counter()
        rt.wakeOn(Interest.Event(iface), event)
        rt.wakeOn(Interest.Claim(iface), claim)
        rt.raise(iface, ord, bytes(1))
        val c = rt.command(iface, ord, bytes(1))
        rt.wakeOn(Interest.Outcome(c), outcome)
        rt.settle(rt.nextClaim(out())!!.id, Result.success(bytes()))
        assertEquals(listOf(1, 1, 1), listOf(event, claim, outcome).map { it.wakes.get() })
    }

    @Test
    fun `the caller handle reads the clock`() {
        val rt = runtime()
        val caller = rt.caller()
        rt.advance(Duration(5))
        assertEquals(rt.now(), caller.now())
    }

    @Test
    fun `a waker is woken after the store lock is released`() {
        // A waker that reads the runtime from another thread inside `wake`. If
        // the waking thread still held the store's monitor, that read could
        // not finish, and `wake` records the timeout instead of hanging.
        val rt = runtime()
        val reader = rt.reader()
        val blocked = AtomicBoolean(false)
        val woken = AtomicInteger()
        val reads = Waker {
            val read = CompletableFuture.supplyAsync { reader.now() }
            runCatching { read.get(2, TimeUnit.SECONDS) }.onFailure { blocked.set(true) }
            woken.incrementAndGet()
        }
        val caller = rt.caller()
        val handler = rt.handler()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))

        val c = caller.command(iface, ord, bytes(1))
        caller.wakeOn(Interest.Outcome(c), reads)
        handler.wakeOn(Interest.Claim(InterfaceNo(2u)), reads)
        source.wakeOn(Interest.Event(iface), reads)

        handler.settle(handler.nextClaim(out())!!.id, Result.success(bytes()))
        caller.command(InterfaceNo(2u), ord, bytes(1))
        rt.raise(iface, ord, bytes(1))

        assertEquals(3, woken.get(), "settle, send, raise")
        assertFalse(blocked.get(), "no waker ran under the store's monitor")
    }
}
