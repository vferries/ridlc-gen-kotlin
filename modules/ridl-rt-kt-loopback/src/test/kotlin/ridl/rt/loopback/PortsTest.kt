package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.Contract
import ridl.rt.port.Changed
import ridl.rt.port.ClaimId
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SettleError
import ridl.rt.port.Watermark
import ridl.rt.sample.Cause
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import java.nio.ByteBuffer

/**
 * The runtime's own tests, each port role exercised against the contract
 * `ridl.rt.port` states for it: `crates/ridl-loopback/tests/ports.rs` of the
 * pinned release, test for test, in the same order and under the same names.
 */
class PortsTest {
    private val iface = InterfaceNo(1u)
    private val ord = Ordinal(1u)
    private val other = Ordinal(2u)

    private fun catalog() = CatalogRef("face.demo", CatalogHash(ByteArray(32)))

    private fun runtime() = Loopback(catalog())

    // ---------------------------------------------------------------------
    // The six tests that came from the double.
    // ---------------------------------------------------------------------

    @Test
    fun `a command is delivered and acknowledged`() {
        val rt = runtime()
        val correlation = rt.command(iface, ord, bytes(1, 2, 3))
        assertNull(rt.ack(correlation), "not yet settled")

        val buf = out(8)
        val claim = rt.nextClaim(buf)!!
        assertEquals(iface, claim.iface)
        assertEquals(ord, claim.ord)
        assertArrayEquals(array(1, 2, 3), buf.written())

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation), "settlement is observable through ack")
    }

    @Test
    fun `a query is delivered and replied`() {
        val rt = runtime()
        val correlation = rt.query(iface, ord, bytes(9))
        val buf = out(8)
        rt.nextClaim(buf)!!.also { rt.settle(it.id, ok(7, 7)) }
        assertArrayEquals(array(9), buf.written())

        val reply = out(8)
        assertEquals(Result.success(2), rt.reply(correlation, reply))
        assertArrayEquals(array(7, 7), reply.written())
        assertNull(rt.ack(correlation), "a query's correlation always answers null from ack")
    }

    @Test
    fun `settle can be made to fail once then succeed`() {
        val rt = runtime()
        val correlation = rt.command(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!

        rt.failNextSettle()
        assertThrows<SettleError> { rt.settle(claim.id, ok()) }
        assertNull(rt.ack(correlation), "a failed settle records no outcome")

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    @Test
    fun `a signal publish and read round trips`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(42))
        rt.commit()

        val buf = out(8)
        assertEquals(Provenance.Live, rt.read(iface, ord, buf).provenance)
        assertArrayEquals(array(42), buf.written())
    }

    @Test
    fun `an event raise and receive round trips`() {
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        rt.raise(iface, ord, bytes(5, 6))

        val buf = out(8)
        val occurrence = rt.next(buf)!!
        assertEquals(iface, occurrence.iface)
        assertEquals(ord, occurrence.ord)
        assertArrayEquals(array(5, 6), buf.written())
    }

    @Test
    fun `the clock is hand driven not wall clock`() {
        val first = runtime()
        Thread.sleep(5)
        val second = runtime()
        assertEquals(first.now(), second.now(), "the clock must not read wall-clock time")

        val before = second.now()
        second.advance(Duration(1_000))
        assertEquals(before.micros + 1_000, second.now().micros)
    }

    // ---------------------------------------------------------------------
    // Signals: staging, the invalid state, the envelope, and a short buffer.
    // ---------------------------------------------------------------------

    @Test
    fun `a signal with no publication reads as init and copies nothing`() {
        val raw = runtime().read(iface, ord, out(8))
        assertEquals(Provenance.Init, raw.provenance)
        assertEquals(0, raw.len)
        assertEquals(Envelope(Timestamp(0), 0u), raw.envelope)
    }

    @Test
    fun `a staged value is not visible until commit`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(7))
        assertEquals(Provenance.Init, rt.read(iface, ord, out(8)).provenance, "staging is private until commit")
        rt.commit()
        assertEquals(Provenance.Live, rt.read(iface, ord, out(8)).provenance)
    }

    @Test
    fun `one commit publishes every staged signal under one timestamp`() {
        val rt = runtime()
        rt.advance(Duration(500))
        rt.set(iface, ord, bytes(1))
        rt.set(iface, other, bytes(2))
        rt.commit()

        val first = rt.read(iface, ord, out(8))
        val second = rt.read(iface, other, out(8))
        assertEquals(Timestamp(500), first.envelope.stamp)
        assertEquals(first.envelope.stamp, second.envelope.stamp)
    }

    @Test
    fun `a channel sequence number counts that channel publications`() {
        val rt = runtime()
        for (value in 1..3) {
            rt.set(iface, ord, bytes(value))
            rt.set(iface, other, bytes(value))
            rt.commit()
        }
        // The first publication is seq 1: seq 0 is a channel with no publication.
        assertEquals(3uL, rt.read(iface, ord, out(8)).envelope.seq)
        assertEquals(3uL, rt.read(iface, other, out(8)).envelope.seq)
    }

    @Test
    fun `invalidate keeps the last good value and reports the declared cause`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(9))
        rt.commit()
        rt.invalidate(iface, ord)
        rt.commit()

        val buf = out(8)
        assertEquals(Provenance.Invalid(Cause.Declared), rt.read(iface, ord, buf).provenance)
        assertArrayEquals(array(9), buf.written(), "the invalid state keeps the last good value (ridl 4.5)")

        rt.set(iface, ord, bytes(10))
        rt.commit()
        assertEquals(Provenance.Live, rt.read(iface, ord, out(8)).provenance, "a set clears the invalid state")
    }

    @Test
    fun `touch republishes the current value without changing it`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(4))
        rt.commit()
        rt.advance(Duration(100))
        rt.touch(iface, ord)
        rt.commit()

        val buf = out(8)
        val raw = rt.read(iface, ord, buf)
        assertArrayEquals(array(4), buf.written(), "touch carries no new value")
        assertEquals(Timestamp(100), raw.envelope.stamp, "touch re-affirms at the new time")
        assertEquals(2uL, raw.envelope.seq, "touch is a publication of the channel")
    }

    @Test
    fun `a touch does not discard a value staged before it`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(7))
        rt.touch(iface, ord)
        rt.commit()

        val buf = out(8)
        assertEquals(Provenance.Live, rt.read(iface, ord, buf).provenance)
        assertArrayEquals(array(7), buf.written())
    }

    @Test
    fun `a touch does not discard an invalidation staged before it`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        rt.commit()
        rt.invalidate(iface, ord)
        rt.touch(iface, ord)
        rt.commit()

        val buf = out(8)
        assertEquals(Provenance.Invalid(Cause.Declared), rt.read(iface, ord, buf).provenance)
        assertArrayEquals(array(1), buf.written())
    }

    @Test
    fun `a set after a touch replaces it`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        rt.commit()
        rt.touch(iface, ord)
        rt.set(iface, ord, bytes(2))
        rt.commit()

        val buf = out(8)
        rt.read(iface, ord, buf)
        assertArrayEquals(array(2), buf.written())
    }

    @Test
    fun `touch on a channel with no publication publishes nothing`() {
        val rt = runtime()
        rt.touch(iface, ord)
        rt.commit()
        assertEquals(Provenance.Init, rt.read(iface, ord, out(8)).provenance)
        assertEquals(0uL, rt.generation(iface), "a commit of only such touches changes nothing")
    }

    @Test
    fun `the clock refuses to run backwards`() {
        val e = assertThrows<IllegalArgumentException> { runtime().advance(Duration(-1)) }
        assertTrue(e.message!!.startsWith("the clock advances forward"))
    }

    @Test
    fun `a short buffer reports what the read needs and consumes nothing`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1, 2, 3, 4))
        rt.commit()

        val short = out(2)
        assertEquals(ReadError.Short(4), assertThrows<ReadError> { rt.read(iface, ord, short) })
        assertEquals(0, short.position(), "and writes nothing")
        assertEquals(4, rt.read(iface, ord, out(8)).len)
    }

    @Test
    fun `every value is unbounded because the runtime has no member table`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        rt.commit()
        assertEquals(Freshness.Unbounded, rt.read(iface, ord, out(8)).freshness)
    }

    // ---------------------------------------------------------------------
    // The two signal extensions.
    // ---------------------------------------------------------------------

    @Test
    fun `a commit advances the interface generation once`() {
        val rt = runtime()
        assertEquals(0uL, rt.generation(iface), "no publication yet")
        rt.set(iface, ord, bytes(1))
        rt.set(iface, other, bytes(2))
        rt.commit()
        assertEquals(1uL, rt.generation(iface), "two signals in one commit advance the generation once")
        rt.set(iface, ord, bytes(3))
        rt.commit()
        assertEquals(2uL, rt.generation(iface))
        assertEquals(0uL, rt.generation(InterfaceNo(2u)), "another interface is untouched")
    }

    @Test
    fun `scan reports the changes since a mark and moves it forward`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        rt.set(iface, other, bytes(2))
        rt.commit()

        val marks = arrayOf(Watermark(iface, 0u, 0u))
        val changes = arrayOfNulls<Changed>(4)
        assertEquals(2, rt.scan(marks, changes))
        assertEquals(ord, changes[0]!!.ord)
        assertEquals(other, changes[1]!!.ord)
        assertEquals(1uL, marks[0].generation, "the mark moved to the current generation")
        assertEquals(1uL, marks[0].seq)
        assertEquals(0, rt.scan(marks, changes), "a second scan with the moved mark reports nothing")

        rt.set(iface, other, bytes(3))
        rt.commit()
        assertEquals(1, rt.scan(marks, changes), "only the signal that changed since the mark")
        assertEquals(other, changes[0]!!.ord)
        assertEquals(2uL, changes[0]!!.seq)
    }

    @Test
    fun `scan writes an interface changes all together or not at all`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        rt.set(iface, other, bytes(2))
        rt.commit()

        val marks = arrayOf(Watermark(iface, 0u, 0u))
        assertEquals(0, rt.scan(marks, arrayOfNulls(1)), "two changes do not fit in one entry, so none is written")
        assertEquals(0uL, marks[0].generation, "and the mark is not moved")
        assertEquals(2, rt.scan(marks, arrayOfNulls(2)))
        assertEquals(1uL, marks[0].generation)
    }

    @Test
    fun `a coherent read answers every ordinal from one publication`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1, 1))
        rt.set(iface, other, bytes(2))
        rt.commit()

        val buf = out(8)
        val samples = arrayOfNulls<RawSample>(2)
        assertEquals(3, rt.readCoherent(iface, listOf(ord, other), buf, samples))
        assertArrayEquals(array(1, 1, 2), buf.written())
        assertEquals(2, samples[0]!!.len)
        assertEquals(1, samples[1]!!.len)
        assertEquals(samples[0]!!.envelope.stamp, samples[1]!!.envelope.stamp, "both come from one publication")
    }

    @Test
    fun `a coherent read reports a short output and a short sample slice`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1, 1))
        rt.set(iface, other, bytes(2))
        rt.commit()

        assertEquals(
            ReadError.Short(3),
            assertThrows<ReadError> { rt.readCoherent(iface, listOf(ord, other), out(2), arrayOfNulls(2)) },
            "the whole set's size, not the first value's",
        )
        assertEquals(
            ReadError.TooFewSamples(2),
            assertThrows<ReadError> { rt.readCoherent(iface, listOf(ord, other), out(8), arrayOfNulls(1)) },
        )
    }

    // ---------------------------------------------------------------------
    // Events.
    // ---------------------------------------------------------------------

    @Test
    fun `an occurrence raised before the subscription is not delivered`() {
        val rt = runtime()
        rt.raise(iface, ord, bytes(1))
        rt.subscribe(iface, listOf(ord))
        assertNull(rt.next(out(8)), "a late joiner receives nothing retroactive on an event")
    }

    @Test
    fun `unsubscribe stops delivery of what is already queued`() {
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        rt.raise(iface, ord, bytes(1))
        rt.unsubscribe(iface, listOf(ord))
        assertNull(rt.next(out(8)))
    }

    @Test
    fun `two sources each receive their own copy of one occurrence`() {
        val rt = runtime()
        val first = rt.source()
        val second = rt.source()
        val sink = rt.sink()
        first.subscribe(iface, listOf(ord))
        second.subscribe(iface, listOf(ord))

        sink.raise(iface, ord, bytes(8))
        assertEquals(1, first.next(out(8))!!.len, "the first source consumes its own copy")
        assertEquals(1, second.next(out(8))!!.len, "and does not consume the second source's")
    }

    @Test
    fun `a short buffer leaves the occurrence for the next call`() {
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        rt.raise(iface, ord, bytes(1, 2, 3))

        assertEquals(ReadError.Short(3), assertThrows<ReadError> { rt.next(out(1)) })
        val buf = out(8)
        rt.next(buf)!!
        assertArrayEquals(array(1, 2, 3), buf.written())
    }

    @Test
    fun `a sink sequence number counts one channel publications`() {
        val rt = runtime()
        val source = rt.source()
        val sink = rt.sink()
        source.subscribe(iface, listOf(ord))

        sink.raise(iface, ord, bytes(1))
        sink.raise(iface, other, bytes(2))
        sink.raise(iface, ord, bytes(3))
        assertEquals(listOf(1uL, 2uL), drain(source), "no gap: the other event has its own counter")
    }

    @Test
    fun `a sink counts its own channel and not another sinks`() {
        val rt = runtime()
        val source = rt.source()
        val first = rt.sink()
        val second = rt.sink()
        source.subscribe(iface, listOf(ord))

        first.raise(iface, ord, bytes(1))
        second.raise(iface, ord, bytes(2))
        first.raise(iface, ord, bytes(3))
        assertEquals(listOf(1uL, 1uL, 2uL), drain(source))
    }

    // ---------------------------------------------------------------------
    // Calls.
    // ---------------------------------------------------------------------

    @Test
    fun `two callers on one provider are two claims under one seq`() {
        val rt = runtime()
        val first = rt.caller()
        val second = rt.caller()
        val handler = rt.handler()

        val a = first.command(iface, ord, bytes(1))
        val b = second.command(iface, ord, bytes(2))
        assertNotEquals(a, b, "the correlations are distinct")

        val buf = out(8)
        val firstClaim = handler.nextClaim(buf)!!
        assertArrayEquals(array(1), buf.written())
        val buf2 = out(8)
        val secondClaim = handler.nextClaim(buf2)!!
        assertArrayEquals(array(2), buf2.written())
        assertEquals(1uL, firstClaim.envelope.seq)
        assertEquals(1uL, secondClaim.envelope.seq, "both callers are on their first call")
        assertNotEquals(firstClaim.id, secondClaim.id, "and they are two claims, not one")

        handler.settle(firstClaim.id, ok())
        handler.settle(secondClaim.id, ok())
        assertEquals(Result.success(Unit), first.ack(a))
        assertEquals(Result.success(Unit), second.ack(b))
    }

    @Test
    fun `a caller sequence number counts that caller calls`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        caller.command(iface, ord, bytes(1))
        caller.query(iface, ord, bytes(2))

        assertEquals(1uL, handler.nextClaim(out(8))!!.envelope.seq)
        assertEquals(2uL, handler.nextClaim(out(8))!!.envelope.seq, "a command and a query share one counter")
    }

    @Test
    fun `a settled outcome reports the contract error the provider settled`() {
        val rt = runtime()
        val correlation = rt.command(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!
        rt.settle(claim.id, Result.failure(Contract.PreconditionFailed))
        assertEquals(Result.failure<Unit>(Contract.PreconditionFailed), rt.ack(correlation))
    }

    @Test
    fun `a claim is presented once and settled once`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!
        assertNull(rt.nextClaim(out(8)), "the claim is presented once")

        rt.settle(claim.id, ok())
        assertEquals(SettleError.UnknownClaim, assertThrows<SettleError> { rt.settle(claim.id, ok()) })
    }

    @Test
    fun `a short buffer leaves the claim for the next call`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1, 2, 3))
        assertEquals(ReadError.Short(3), assertThrows<ReadError> { rt.nextClaim(out(1)) })

        val buf = out(8)
        rt.nextClaim(buf)!!
        assertArrayEquals(array(1, 2, 3), buf.written())
    }

    @Test
    fun `forget releases a settled correlation`() {
        val rt = runtime()
        val correlation = rt.command(iface, ord, bytes(1))
        rt.settle(rt.nextClaim(out(8))!!.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))

        rt.forget(correlation)
        assertNull(rt.ack(correlation), "the outcome is no longer retrievable")
    }

    @Test
    fun `forget before the claim is presented leaves the call for the provider`() {
        val rt = runtime()
        val correlation = rt.command(iface, ord, bytes(1))
        rt.forget(correlation)

        val buf = out(8)
        val claim = rt.nextClaim(buf) ?: error("the call is still presented")
        assertArrayEquals(array(1), buf.written())
        rt.settle(claim.id, ok())
        assertNull(rt.ack(correlation), "but the caller asked not to be told")
    }

    @Test
    fun `forget between the claim and the settlement leaves the settlement valid`() {
        val rt = runtime()
        val correlation = rt.query(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!
        rt.forget(correlation)

        rt.settle(claim.id, ok(7))
        assertNull(rt.reply(correlation, out(8)))
    }

    @Test
    fun `a claim that was never presented cannot be settled`() {
        val rt = runtime()
        val correlation = rt.command(iface, ord, bytes(1))
        assertEquals(
            SettleError.UnknownClaim,
            assertThrows<SettleError> { rt.settle(ClaimId(correlation.value), ok()) },
        )
        assertNull(rt.ack(correlation), "and nothing was acknowledged")

        rt.settle(rt.nextClaim(out(8))!!.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    @Test
    fun `an injected settle failure is not spent on an unknown claim`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!

        rt.failNextSettle()
        assertEquals(
            SettleError.UnknownClaim,
            assertThrows<SettleError> { rt.settle(ClaimId(9999), ok()) },
            "the claim is checked before the injected failure is consumed",
        )
        assertEquals(
            SettleError.TooLarge(0),
            assertThrows<SettleError> { rt.settle(claim.id, ok()) },
            "so the injected failure still has the next real settlement to fail",
        )
    }

    @Test
    fun `a handler cannot settle another handlers claim`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(InterfaceNo(2u), listOf(ord))

        val correlation = caller.command(iface, ord, bytes(1))
        val claim = first.nextClaim(out(8))!!
        assertEquals(SettleError.UnknownClaim, assertThrows<SettleError> { second.settle(claim.id, ok()) })
        assertNull(caller.ack(correlation), "and nothing was acknowledged in the caller's name")

        first.settle(claim.id, ok())
        assertEquals(Result.success(Unit), caller.ack(correlation))
    }

    @Test
    fun `serve records what it was asked to present`() {
        val handler = runtime().handler()
        handler.serve(iface, listOf(ord, other))
        handler.serve(iface, listOf(ord))
        assertEquals(listOf(iface to ord, iface to other), handler.served)
    }

    @Test
    fun `a handler that served nothing is presented every call`() {
        val rt = runtime()
        rt.caller().command(InterfaceNo(2u), other, bytes(1))
        assertEquals(InterfaceNo(2u), rt.handler().nextClaim(out(8))!!.iface)
    }

    @Test
    fun `two handlers each receive only what they served`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(InterfaceNo(2u), listOf(ord))

        caller.command(InterfaceNo(2u), ord, bytes(7))
        caller.command(iface, ord, bytes(8))

        val buf = out(8)
        assertEquals(iface, first.nextClaim(buf)!!.iface, "the call the first handler served")
        assertArrayEquals(array(8), buf.written())
        val buf2 = out(8)
        assertEquals(InterfaceNo(2u), second.nextClaim(buf2)!!.iface, "and the second handler's own")
        assertArrayEquals(array(7), buf2.written())

        assertNull(first.nextClaim(out(8)), "neither handler consumed the other's call")
        assertNull(second.nextClaim(out(8)))
    }

    // ---------------------------------------------------------------------
    // `fixed`.
    // ---------------------------------------------------------------------

    @Test
    fun `a provisioned fixed reads back and an unprovisioned one reports it`() {
        val rt = runtime()
        assertEquals(
            ReadError.Contract(Contract.UnknownInteraction),
            assertThrows<ReadError> { rt.readFixed(iface, ord, out(8)) },
            "nothing was provisioned at that ordinal",
        )

        rt.provisionFixed(iface, ord, bytes(1, 2))
        val buf = out(8)
        assertEquals(2, rt.readFixed(iface, ord, buf))
        assertArrayEquals(array(1, 2), buf.written())
        assertEquals(ReadError.Short(2), assertThrows<ReadError> { rt.readFixed(iface, ord, out(1)) })
    }

    // ---------------------------------------------------------------------
    // The handle model.
    // ---------------------------------------------------------------------

    @Test
    fun `the catalog is the one the runtime was built with`() {
        val rt = runtime()
        assertEquals(catalog(), rt.catalog)
        val handles = rt.split()
        for (handle in listOf(
            handles.reader, handles.writer, handles.source, handles.sink, handles.caller, handles.handler,
        )) {
            assertEquals(catalog(), handle.catalog)
        }
    }

    @Test
    fun `a writer handle publishes on one thread while a reader reads on another`() {
        val handles = runtime().split()
        val reader = handles.reader
        val writer = handles.writer

        val publisher = Thread {
            for (value in 1..50) {
                // Four bytes that must agree: a read that saw part of one
                // publication and part of the next would not.
                writer.set(iface, ord, ByteBuffer.wrap(ByteArray(4) { value.toByte() }))
                writer.commit()
            }
        }
        publisher.start()

        var seen = 0
        while (seen < 200) {
            val buf = out(8)
            val raw = reader.read(iface, ord, buf)
            if (raw.len == 0) continue
            val bytes = buf.written()
            assertEquals(4, raw.len)
            val value = bytes[0].toInt()
            assertTrue(value in 1..50)
            assertArrayEquals(ByteArray(4) { value.toByte() }, bytes, "a publication is read whole or not at all")
            assertEquals(value.toULong(), raw.envelope.seq, "and its envelope belongs to the value read")
            seen += 1
        }

        publisher.join()
        val buf = out(8)
        assertEquals(50uL, reader.read(iface, ord, buf).envelope.seq)
        assertArrayEquals(ByteArray(4) { 50 }, buf.written())
    }

    @Test
    fun `a reader handle is shared between threads`() {
        val handles = runtime().split()
        handles.writer.set(iface, ord, bytes(3))
        handles.writer.commit()

        val results = arrayOfNulls<ByteArray>(4)
        val threads = (0 until 4).map { index ->
            Thread {
                val buf = out(8)
                handles.reader.read(iface, ord, buf)
                results[index] = buf.written()
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        for (result in results) assertArrayEquals(array(3), result)
    }

    // ---------------------------------------------------------------------
    // Kotlin's own: the ByteBuffer rule, split, close, and Wakeable.
    // ---------------------------------------------------------------------

    @Test
    fun `an input buffer is read from its position and left where it was`() {
        val rt = runtime()
        val input = ByteBuffer.wrap(array(0, 0, 5, 6)).position(2)
        rt.set(iface, ord, input)
        rt.commit()
        assertEquals(2, input.position(), "the caller's buffer is not consumed")

        val buf = out(8)
        rt.read(iface, ord, buf)
        assertArrayEquals(array(5, 6), buf.written(), "only the bytes from the position to the limit")
    }

    @Test
    fun `an output buffer is written from its position and advanced`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(9, 9))
        rt.commit()
        val buf = ByteBuffer.allocate(8).position(3)
        assertEquals(2, rt.read(iface, ord, buf).len)
        assertEquals(5, buf.position())
        assertEquals(9, buf.get(3).toInt())
    }

    @Test
    fun `a staged value is copied, so a later change to the caller's buffer does not reach it`() {
        val rt = runtime()
        val backing = array(1)
        rt.set(iface, ord, ByteBuffer.wrap(backing))
        backing[0] = 2
        rt.commit()
        val buf = out(8)
        rt.read(iface, ord, buf)
        assertArrayEquals(array(1), buf.written())
    }

    @Test
    fun `the aggregate refuses its port methods once split`() {
        val rt = runtime()
        val handles = rt.split()
        assertThrows<IllegalStateException> { rt.commit() }
        assertThrows<IllegalStateException> { rt.split() }
        handles.writer.set(iface, ord, bytes(1))
        handles.writer.commit()
        assertEquals(Provenance.Live, rt.reader().read(iface, ord, out(8)).provenance, "the factories still work")
    }

    @Test
    fun `a closed source receives nothing more`() {
        val rt = runtime()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        source.close()
        rt.raise(iface, ord, bytes(1))
        assertNull(source.next(out(8)))
    }

    @Test
    fun `a claim is settled with a call error and nothing else`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!
        assertThrows<IllegalArgumentException> { rt.settle(claim.id, Result.failure(RuntimeException("no"))) }
        rt.settle(claim.id, ok())
    }

    @Test
    fun `a waker runs after every commit, raise, send and settlement until closed`() {
        val rt = runtime()
        var wakes = 0
        val handle = rt.onChange { wakes += 1 }

        rt.set(iface, ord, bytes(1))
        assertEquals(0, wakes, "staging is not a change a waiter can see")
        rt.commit()
        rt.raise(iface, ord, bytes(1))
        val correlation = rt.command(iface, ord, bytes(1))
        rt.settle(rt.nextClaim(out(8))!!.id, ok())
        assertEquals(4, wakes)
        assertEquals(Result.success(Unit), rt.ack(correlation))

        handle.close()
        rt.commit()
        assertEquals(4, wakes, "a closed waker is not called")
    }

    // ---------------------------------------------------------------------

    private fun array(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(array(*values))

    private fun out(size: Int): ByteBuffer = ByteBuffer.allocate(size)

    private fun ok(vararg values: Int): Result<ByteBuffer> = Result.success(bytes(*values))

    /** The bytes a port wrote into this buffer: from 0 to its position. */
    private fun ByteBuffer.written(): ByteArray = ByteArray(position()).also { duplicate().flip().get(it) }

    private fun drain(source: ridl.rt.port.EventSource): List<ULong> =
        generateSequence { source.next(out(8)) }.map { it.envelope.seq }.toList()
}
