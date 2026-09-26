package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.Transport
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loopback's `Wakeable` (story E11.16) and `Transport.Busy` carried back
 * to a caller: the "Waking" tests of `crates/ridl-loopback/tests/ports.rs` on
 * ridl `main` at c2543c2, ahead of the release that carries them, in the same
 * order and under the same names.
 *
 * Every handle is `Wakeable`, and stores one waker per kind of key it
 * observes: the source one `Event` waker, the handler one `Claim` waker, the
 * caller an `Outcome` waker with each call. A change to any key of a kind
 * wakes that kind's waker, a key a handle does not observe is woken at once,
 * and `Slot` is always woken at once. Where a Rust test counts the references
 * to a waker to prove a dropped handle released it, the test here counts the
 * wakes that still reach it after `close`.
 */
class WakeableTest {
    private val iface = InterfaceNo(1u)
    private val iface2 = InterfaceNo(2u)
    private val ord = Ordinal(1u)
    private val other = Ordinal(2u)

    private fun runtime() = Loopback(CatalogRef("face.demo", CatalogHash(ByteArray(32))))

    /** A waker that counts its wakes. */
    private class Count : Waker {
        private val count = AtomicInteger()

        val wakes: Int get() = count.get()

        override fun wake() {
            count.incrementAndGet()
        }
    }

    private fun out() = ByteBuffer.allocate(8)

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

    private fun ok() = Result.success(bytes())

    /** The bytes a port wrote into this buffer: from 0 to its position. */
    private fun ByteBuffer.written(): ByteArray = ByteArray(position()).also { duplicate().flip().get(it) }

    @Test
    fun `a waiter on an outcome is woken exactly once by its settlement`() {
        val h = runtime().split()
        val count = Count()
        val c = h.caller.command(iface, ord, bytes(1))
        h.caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes, "nothing is known about the call yet")

        val claim = h.handler.nextClaim(out())!!
        assertEquals(0, count.wakes, "a presented call has no outcome yet")

        h.handler.settle(claim.id, ok())
        assertEquals(1, count.wakes, "the settlement wakes the waiter")
        assertEquals(Result.success(Unit), h.caller.ack(c))

        h.caller.command(iface, ord, bytes(2))
        h.handler.settle(h.handler.nextClaim(out())!!.id, ok())
        assertEquals(1, count.wakes, "a waiter is woken at most once")
    }

    @Test
    fun `each settlement wakes only its own calls waiter`() {
        val h = runtime().split()
        val firstCall = h.caller.command(iface, ord, bytes(1))
        val secondCall = h.caller.command(iface, ord, bytes(2))
        val first = Count()
        val second = Count()
        h.caller.wakeOn(Interest.Outcome(firstCall), first)
        h.caller.wakeOn(Interest.Outcome(secondCall), second)
        assertEquals(0 to 0, first.wakes to second.wakes, "two calls, two wakers, no displacement")

        val claimOne = h.handler.nextClaim(out())!!
        val claimTwo = h.handler.nextClaim(out())!!
        h.handler.settle(claimTwo.id, ok())
        assertEquals(0 to 1, first.wakes to second.wakes, "only the second call is settled")
        h.handler.settle(claimOne.id, ok())
        assertEquals(1 to 1, first.wakes to second.wakes)
    }

    @Test
    fun `a waiter registered after the settlement is woken at once`() {
        val h = runtime().split()
        val c = h.caller.query(iface, ord, bytes(1))
        h.handler.settle(h.handler.nextClaim(out())!!.id, Result.success(bytes(7)))

        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(1, count.wakes, "the outcome is already known")
        assertEquals(Result.success(1), h.caller.reply(c, out()))
    }

    @Test
    fun `a second registration under a key wakes the displaced waker`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val handler = rt.handler()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))

        // An outcome.
        val c = caller.command(iface, ord, bytes(1))
        var first = Count()
        var second = Count()
        caller.wakeOn(Interest.Outcome(c), first)
        caller.wakeOn(Interest.Outcome(c), second)
        assertEquals(1, first.wakes, "the displaced waker is woken")
        assertEquals(0, second.wakes)
        handler.settle(handler.nextClaim(out())!!.id, ok())
        assertEquals(1, first.wakes, "the displaced waker is not stored")
        assertEquals(1, second.wakes, "the settlement wakes the stored waker")

        // An event.
        first = Count()
        second = Count()
        source.wakeOn(Interest.Event(iface), first)
        source.wakeOn(Interest.Event(iface), second)
        assertEquals(1, first.wakes, "the displaced waker is woken")
        sink.raise(iface, ord, bytes(1))
        assertEquals(1, first.wakes, "the displaced waker is not stored")
        assertEquals(1, second.wakes, "the raise wakes the stored waker")

        // A claim.
        first = Count()
        second = Count()
        handler.wakeOn(Interest.Claim(iface), first)
        handler.wakeOn(Interest.Claim(iface), second)
        assertEquals(1, first.wakes, "the displaced waker is woken")
        caller.command(iface, ord, bytes(2))
        assertEquals(1, first.wakes, "the displaced waker is not stored")
        assertEquals(1, second.wakes, "the send wakes the stored waker")
    }

    @Test
    fun `a registration of the waker already stored does not wake it`() {
        val h = runtime().split()
        val c = h.caller.command(iface, ord, bytes(1))
        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        h.caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes, "the same task registered twice")

        h.handler.settle(h.handler.nextClaim(out())!!.id, ok())
        assertEquals(1, count.wakes, "and is still woken by the settlement")
    }

    @Test
    fun `the same task registering an event or claim key again wakes nothing`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface, listOf(ord))

        val event = Count()
        source.wakeOn(Interest.Event(iface), event)
        source.wakeOn(Interest.Event(iface), event)
        assertEquals(0, event.wakes, "`Event`: the same task is refreshed")
        sink.raise(iface, ord, bytes(1))
        assertEquals(1, event.wakes, "and the raise wakes it")

        val claim = Count()
        handler.wakeOn(Interest.Claim(iface), claim)
        handler.wakeOn(Interest.Claim(iface), claim)
        assertEquals(0, claim.wakes, "`Claim`: the same task is refreshed")
        caller.command(iface, ord, bytes(1))
        assertEquals(1, claim.wakes, "and the send wakes it")
    }

    @Test
    fun `a raise wakes a subscribed source and not an unsubscribed one`() {
        val rt = runtime()
        val sink = rt.sink()
        val subscribed = rt.source()
        val elsewhere = rt.source()
        subscribed.subscribe(iface, listOf(ord))
        // Subscribed to another event of the same interface: its key is the
        // same `Event(iface)`, and the occurrence below is still not its.
        elsewhere.subscribe(iface, listOf(other))
        val unsubscribed = rt.source()

        val woken = Count()
        val otherCount = Count()
        val none = Count()
        subscribed.wakeOn(Interest.Event(iface), woken)
        elsewhere.wakeOn(Interest.Event(iface), otherCount)
        unsubscribed.wakeOn(Interest.Event(iface), none)

        sink.raise(iface, ord, bytes(1))
        assertEquals(1, woken.wakes, "the subscribed source is woken")
        assertEquals(0, otherCount.wakes, "a source subscribed to another event is not")
        assertEquals(0, none.wakes, "an unsubscribed source is not")
        assertNotNull(subscribed.next(out()))
    }

    @Test
    fun `a source registered under two interfaces is woken by either`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        source.subscribe(iface2, listOf(ord))
        val count = Count()

        for ((turn, changed) in listOf(iface, iface2).withIndex()) {
            source.wakeOn(Interest.Event(iface), count)
            source.wakeOn(Interest.Event(iface2), count)
            assertEquals(turn, count.wakes, "the same task registered twice")
            sink.raise(changed, ord, bytes(1))
            assertEquals(turn + 1, count.wakes, "an occurrence of interface $changed wakes it")
            while (source.next(out()) != null) continue
        }
    }

    @Test
    fun `a handler registered under two interfaces is woken by either`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(ord))
        handler.serve(iface2, listOf(ord))
        val count = Count()

        for ((turn, changed) in listOf(iface, iface2).withIndex()) {
            handler.wakeOn(Interest.Claim(iface), count)
            handler.wakeOn(Interest.Claim(iface2), count)
            assertEquals(turn, count.wakes, "the same task registered twice")
            caller.command(changed, ord, bytes(1))
            assertEquals(turn + 1, count.wakes, "a call on interface $changed wakes it")
            handler.settle(handler.nextClaim(out())!!.id, ok())
        }
    }

    @Test
    fun `a registration under one interface is woken by a change on another`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface2, listOf(ord))
        handler.serve(iface2, listOf(ord))

        val event = Count()
        source.wakeOn(Interest.Event(iface), event)
        sink.raise(iface2, ord, bytes(1))
        assertEquals(1, event.wakes, "an occurrence of interface 2 wakes it")

        val claim = Count()
        handler.wakeOn(Interest.Claim(iface), claim)
        caller.command(iface2, ord, bytes(1))
        assertEquals(1, claim.wakes, "a call on interface 2 wakes it")
    }

    @Test
    fun `two tasks under two interfaces of one kind share the one slot`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface, listOf(ord))
        handler.serve(iface, listOf(ord))

        var a = Count()
        var b = Count()
        source.wakeOn(Interest.Event(iface), a)
        source.wakeOn(Interest.Event(iface2), b)
        assertEquals(1 to 0, a.wakes to b.wakes, "B displaces A")
        sink.raise(iface, ord, bytes(1))
        assertEquals(1 to 1, a.wakes to b.wakes, "interface 1 wakes B")

        a = Count()
        b = Count()
        handler.wakeOn(Interest.Claim(iface), a)
        handler.wakeOn(Interest.Claim(iface2), b)
        assertEquals(1 to 0, a.wakes to b.wakes, "B displaces A")
        caller.command(iface, ord, bytes(1))
        assertEquals(1 to 1, a.wakes to b.wakes, "interface 1 wakes B")
    }

    @Test
    fun `a waiting occurrence of another interface wakes an event registration at once`() {
        val rt = runtime()
        val source = rt.source()
        source.subscribe(iface2, listOf(ord))
        rt.sink().raise(iface2, ord, bytes(1))

        val count = Count()
        source.wakeOn(Interest.Event(iface), count)
        assertEquals(1, count.wakes, "an occurrence of another interface is waiting")
    }

    @Test
    fun `a waiting call on another interface wakes a claim registration at once`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface2, listOf(ord))
        rt.caller().command(iface2, ord, bytes(1))

        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(1, count.wakes, "a call on another interface is waiting")
    }

    @Test
    fun `an event or claim waiter is cleared when woken`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface, listOf(ord))
        handler.serve(iface, listOf(ord))

        val event = Count()
        source.wakeOn(Interest.Event(iface), event)
        sink.raise(iface, ord, bytes(1))
        sink.raise(iface, ord, bytes(2))
        assertEquals(1, event.wakes, "the first raise cleared the waker")

        val claim = Count()
        handler.wakeOn(Interest.Claim(iface), claim)
        caller.command(iface, ord, bytes(1))
        caller.command(iface, ord, bytes(2))
        assertEquals(1, claim.wakes, "the first send cleared the waker")
    }

    @Test
    fun `a drop returns only the dropped handlers claims and wakes every serving handler`() {
        val rt = runtime()
        val caller = rt.caller()
        val keeper = rt.handler()
        val dropped = rt.handler()
        val otherHandler = rt.handler()
        val elsewhere = rt.handler()
        keeper.serve(iface, listOf(ord))
        dropped.serve(iface, listOf(ord))
        otherHandler.serve(iface, listOf(ord))
        elsewhere.serve(iface, listOf(other))

        val kept = caller.command(iface, ord, bytes(1))
        val keptClaim = keeper.nextClaim(out())!!
        caller.command(iface, ord, bytes(2))
        dropped.nextClaim(out())!!

        val first = Count()
        val second = Count()
        val third = Count()
        keeper.wakeOn(Interest.Claim(iface), first)
        otherHandler.wakeOn(Interest.Claim(iface), second)
        elsewhere.wakeOn(Interest.Claim(iface), third)
        dropped.close()
        assertEquals(1, first.wakes, "every serving handler is woken: the first")
        assertEquals(1, second.wakes, "every serving handler is woken: the second")
        assertEquals(0, third.wakes, "a handler serving another member is not woken")
        assertNull(elsewhere.nextClaim(out()), "and is presented nothing")

        val buf = out()
        assertNotNull(otherHandler.nextClaim(buf), "returned")
        assertArrayEquals(byteArrayOf(2), buf.written(), "only the dropped handler's call")
        assertNull(otherHandler.nextClaim(out()), "the call another handler holds stays with it")
        keeper.settle(keptClaim.id, ok())
        assertEquals(Result.success(Unit), caller.ack(kept))
    }

    @Test
    fun `a waiter is cleared when woken`() {
        val h = runtime().split()
        val c = h.caller.command(iface, ord, bytes(1))
        val first = Count()
        h.caller.wakeOn(Interest.Outcome(c), first)
        h.handler.settle(h.handler.nextClaim(out())!!.id, ok())
        assertEquals(1, first.wakes)

        val second = Count()
        h.caller.wakeOn(Interest.Outcome(c), second)
        assertEquals(1, second.wakes, "the outcome is known")
        assertEquals(1, first.wakes, "the first waker was cleared when woken")
    }

    @Test
    fun `a waiter woken at once is not stored`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        sink.raise(iface, ord, bytes(1))
        val count = Count()
        source.wakeOn(Interest.Event(iface), count)
        assertEquals(1, count.wakes, "an occurrence is waiting")

        sink.raise(iface, ord, bytes(2))
        assertEquals(1, count.wakes, "a waker woken at once was not stored")
    }

    @Test
    fun `a registration on a forgotten call is woken at once`() {
        val h = runtime().split()
        val c = h.caller.command(iface, ord, bytes(1))
        h.caller.forget(c)
        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(1, count.wakes, "no outcome will be recorded")
    }

    @Test
    fun `every subscribed source and every serving handler is woken`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val firstSource = rt.source()
        val secondSource = rt.source()
        firstSource.subscribe(iface, listOf(ord))
        secondSource.subscribe(iface, listOf(ord))
        val firstHandler = rt.handler()
        val secondHandler = rt.handler()
        firstHandler.serve(iface, listOf(ord))
        secondHandler.serve(iface, listOf(ord))

        val wakers = List(4) { Count() }
        firstSource.wakeOn(Interest.Event(iface), wakers[0])
        secondSource.wakeOn(Interest.Event(iface), wakers[1])
        firstHandler.wakeOn(Interest.Claim(iface), wakers[2])
        secondHandler.wakeOn(Interest.Claim(iface), wakers[3])

        sink.raise(iface, ord, bytes(1))
        assertEquals(1, wakers[0].wakes, "the first source")
        assertEquals(1, wakers[1].wakes, "the second source")

        caller.command(iface, ord, bytes(1))
        assertEquals(1, wakers[2].wakes, "the first handler")
        assertEquals(1, wakers[3].wakes, "the second handler")
    }

    @Test
    fun `a query wakes the handler that serves it`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface, listOf(ord))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        rt.caller().query(iface, ord, bytes(1))
        assertEquals(1, count.wakes, "the query wakes the serving handler")
    }

    @Test
    fun `a claim registration is not woken by a call the handler does not serve`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        rt.caller().command(iface, ord, bytes(1))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes, "the waiting call is not this handler's")
    }

    @Test
    fun `a serve that admits nothing does not wake the handler`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        handler.serve(iface, listOf(ord))
        assertEquals(0, count.wakes, "no call is waiting")
    }

    @Test
    fun `a dropped handler returns its claims to the waiting calls`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(ord))

        val c = caller.command(iface, ord, bytes(1))
        first.nextClaim(out())!!
        val later = caller.command(iface, ord, bytes(2))
        first.nextClaim(out())!!
        assertNull(second.nextClaim(out()), "both calls are held by the first handler")
        val count = Count()
        second.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes)
        // The caller waits on the first call across the drop: the return does
        // not wake it, and the settlement by the handler that takes it does.
        val outcome = Count()
        caller.wakeOn(Interest.Outcome(c), outcome)

        first.close()
        assertEquals(1, count.wakes, "the returned claims wake a serving handler")
        assertEquals(0, outcome.wakes, "a returned claim is not an outcome")

        // They return in send order, and are settled by the handler that takes them.
        var buf = out()
        val again = second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(1), buf.written(), "the earlier call first")
        second.settle(again.id, ok())
        assertEquals(1, outcome.wakes, "the settlement by the second handler wakes the caller")
        buf = out()
        val then = second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(2), buf.written())
        second.settle(then.id, ok())
        assertEquals(Result.success(Unit), caller.ack(c))
        assertEquals(Result.success(Unit), caller.ack(later))
    }

    @Test
    fun `a send wakes the handler that serves the member`() {
        val rt = runtime()
        val caller = rt.caller()
        val serving = rt.handler()
        val elsewhere = rt.handler()
        serving.serve(iface, listOf(ord))
        // Serves another member of the same interface: its key is the same
        // `Claim(iface)`, and the call below is still not presented to it.
        elsewhere.serve(iface, listOf(other))

        val woken = Count()
        val otherCount = Count()
        serving.wakeOn(Interest.Claim(iface), woken)
        elsewhere.wakeOn(Interest.Claim(iface), otherCount)

        caller.command(iface, ord, bytes(1))
        assertEquals(1, woken.wakes, "the serving handler is woken")
        assertEquals(0, otherCount.wakes, "a handler serving another member is not")
        assertNotNull(serving.nextClaim(out()))
    }

    @Test
    fun `a serve that admits a waiting call wakes the handler`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        caller.command(iface, ord, bytes(1))
        assertEquals(0, count.wakes, "the handler does not serve the member")

        handler.serve(iface, listOf(ord))
        assertEquals(1, count.wakes, "the call is now waiting for this handler")
    }

    @Test
    fun `a caller handle reads the clock`() {
        val rt = runtime()
        val caller = rt.caller()
        rt.advance(Duration(250))
        assertEquals(Timestamp(250), caller.now())
        assertEquals(rt.now(), caller.now())
    }

    @Test
    fun `a registration whose key already holds is woken at once`() {
        val rt = runtime()
        val caller = rt.caller()
        val sink = rt.sink()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface, listOf(ord))

        // The call table has no bound, so a slot is always free.
        val slot = Count()
        caller.wakeOn(Interest.Slot, slot)
        assertEquals(1, slot.wakes, "a slot is free")

        sink.raise(iface, ord, bytes(1))
        val event = Count()
        source.wakeOn(Interest.Event(iface), event)
        assertEquals(1, event.wakes, "an occurrence is waiting")

        caller.command(iface, ord, bytes(1))
        val claim = Count()
        handler.wakeOn(Interest.Claim(iface), claim)
        assertEquals(1, claim.wakes, "a call is waiting")

        // A correlation that names no call in flight has no outcome to wait
        // for; storing its waker would leave it waiting on nothing.
        val unknown = Count()
        caller.wakeOn(Interest.Outcome(Correlation(99)), unknown)
        assertEquals(1, unknown.wakes, "no call has that correlation")
    }

    @Test
    fun `a forgotten call wakes its waiter`() {
        val h = runtime().split()
        val c = h.caller.command(iface, ord, bytes(1))
        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        h.caller.forget(c)
        assertEquals(1, count.wakes, "the forget wakes the waiter")
    }

    @Test
    fun `a key no role of the handle observes is woken at once`() {
        val h = runtime().split()
        val count = Count()
        h.reader.wakeOn(Interest.Slot, count)
        h.writer.wakeOn(Interest.Event(iface), count)
        h.sink.wakeOn(Interest.Claim(iface), count)
        h.caller.wakeOn(Interest.Event(iface), count)
        h.caller.wakeOn(Interest.Claim(iface), count)
        h.source.wakeOn(Interest.Slot, count)
        h.source.wakeOn(Interest.Claim(iface), count)
        h.source.wakeOn(Interest.Outcome(Correlation(0)), count)
        h.handler.wakeOn(Interest.Slot, count)
        h.handler.wakeOn(Interest.Event(iface), count)
        h.handler.wakeOn(Interest.Outcome(Correlation(0)), count)
        assertEquals(11, count.wakes)
    }

    @Test
    fun `a dropped handler leaves no waiter behind`() {
        // `close` removes the handler's state, its stored waker with it, and
        // returns its claims; the handler's own state is gone before the
        // return wakes the handlers that serve the member.
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        caller.command(iface, ord, bytes(1))
        handler.nextClaim(out())!!
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        handler.close()
        assertEquals(0, count.wakes, "the returned claim does not wake it")
        caller.command(iface, ord, bytes(2))
        assertEquals(0, count.wakes, "nothing wakes a closed handler's waker")
    }

    @Test
    fun `a dropped source leaves no waiter behind`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        val count = Count()
        source.wakeOn(Interest.Event(iface), count)
        source.close()
        sink.raise(iface, ord, bytes(1))
        assertEquals(0, count.wakes, "nothing wakes a closed source's waker")
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
    fun `a returned claim keeps its place by send order`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()

        caller.command(iface, ord, bytes(1))
        first.nextClaim(out())!!
        caller.command(iface, ord, bytes(2))
        first.close()

        val buf = out()
        second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(1), buf.written(), "the returned call was sent first, so it is presented first")
    }

    @Test
    fun `the aggregate routes each key to the handle that observes it`() {
        val rt = runtime()

        val c = rt.command(iface, ord, bytes(1))
        val outcome = Count()
        rt.wakeOn(Interest.Outcome(c), outcome)
        assertEquals(0, outcome.wakes, "stored, not woken at once")
        rt.settle(rt.nextClaim(out())!!.id, ok())
        assertEquals(1, outcome.wakes, "woken by the settlement")

        rt.subscribe(iface, listOf(ord))
        val event = Count()
        rt.wakeOn(Interest.Event(iface), event)
        assertEquals(0, event.wakes, "stored, not woken at once")
        rt.raise(iface, ord, bytes(1))
        assertEquals(1, event.wakes, "woken by the raise")

        val claim = Count()
        rt.wakeOn(Interest.Claim(iface), claim)
        assertEquals(0, claim.wakes, "stored, not woken at once")
        rt.caller().command(iface, ord, bytes(1))
        assertEquals(1, claim.wakes, "woken by the send")

        val slot = Count()
        rt.wakeOn(Interest.Slot, slot)
        assertEquals(1, slot.wakes, "the caller wakes it at once: a slot is free")
    }

    /**
     * A waker that, when woken, reads the clock from another thread and
     * reports whether that read finished within a second. Under the store's
     * monitor, the read could not finish until the wake returned.
     */
    private fun lockProbe(rt: Loopback): Pair<Waker, LinkedBlockingQueue<Boolean>> {
        val reader = rt.reader()
        val done = LinkedBlockingQueue<Boolean>()
        val waker = Waker {
            val read = CompletableFuture.supplyAsync { reader.now() }
            done.offer(runCatching { read.get(1, TimeUnit.SECONDS) }.isSuccess)
        }
        return waker to done
    }

    private fun assertReleased(done: LinkedBlockingQueue<Boolean>, path: String) {
        assertTrue(done.poll(5, TimeUnit.SECONDS) == true, "$path: the waker ran with the store's monitor released")
    }

    @Test
    fun `a waker is woken after the lock is released`() {
        val rt = runtime()
        val (waker, done) = lockProbe(rt)
        val h = rt.split()
        val c = h.caller.command(iface, ord, bytes(1))
        h.caller.wakeOn(Interest.Outcome(c), waker)
        h.handler.settle(h.handler.nextClaim(out())!!.id, ok())
        assertReleased(done, "a settlement")
    }

    @Test
    fun `every wake is run with the lock released`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        source.subscribe(iface, listOf(ord))
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(other))

        var (waker, done) = lockProbe(rt)
        source.wakeOn(Interest.Event(iface), waker)
        sink.raise(iface, ord, bytes(1))
        assertReleased(done, "a raise")

        lockProbe(rt).let { (w, d) -> source.wakeOn(Interest.Event(iface), w); assertReleased(d, "a registration whose key already holds") }

        lockProbe(rt).let { (w, d) -> first.wakeOn(Interest.Claim(iface), w); caller.command(iface, ord, bytes(1)); assertReleased(d, "a command") }

        first.nextClaim(out())!!
        val c = lockProbe(rt).let { (w, d) ->
            first.wakeOn(Interest.Claim(iface), w)
            caller.query(iface, ord, bytes(1)).also { assertReleased(d, "a query") }
        }

        lockProbe(rt).let { (w, d) -> caller.wakeOn(Interest.Outcome(c), w); caller.forget(c); assertReleased(d, "a forget") }

        lockProbe(rt).let { (w, d) -> second.wakeOn(Interest.Claim(iface), w); second.serve(iface, listOf(ord)); assertReleased(d, "a serve") }

        // The second handler takes the query, and its close returns it to the first.
        second.nextClaim(out())!!
        val probe = lockProbe(rt)
        waker = probe.first
        done = probe.second
        first.wakeOn(Interest.Claim(iface), waker)
        assertNull(first.nextClaim(out()))
        second.close()
        assertReleased(done, "a handler's close")
    }
}
