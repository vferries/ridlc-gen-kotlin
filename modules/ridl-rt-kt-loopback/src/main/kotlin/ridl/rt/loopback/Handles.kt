// The six role handles and their port implementations: the spelling of
// `crates/ridl-loopback/src/handle.rs`.
//
// A runtime presents one handle type per port role (ADR-0021 decision 12).
// The six here group the eleven roles as that decision derives the threading
// split: the five roles with a mutating method take a handle each, and the six
// whose methods only read share one.
//
// | Handle          | Port roles beside `Attached`                                                   | Threading          |
// | --------------- | ------------------------------------------------------------------------------ | ------------------ |
// | `ReaderHandle`  | `Clock`, `SignalReader`, `FixedReader`, `ScannableSignals`, `CoherentSignals` | any threads at once |
// | `WriterHandle`  | `SignalWriter`                                                                 | one thread at a time |
// | `SourceHandle`  | `EventSource`, `Wakeable`                                                      | one thread at a time |
// | `SinkHandle`    | `EventSink`                                                                    | one thread at a time |
// | `CallerHandle`  | `Caller`, `Clock`, `Wakeable`                                                  | one thread at a time |
// | `HandlerHandle` | `Handler`, `Wakeable`                                                          | one thread at a time |
//
// `Wakeable` is on the three handles a task waits on, each for the keys of its
// own role: the source for `Interest.Event`, the caller for `Interest.Outcome`
// and `Interest.Slot`, the handler for `Interest.Claim`. A key a handle's role
// does not carry is not stored and never woken, because no change of it is
// visible through that handle. `CallerHandle` carries `Clock` too, because a
// client that waits for an outcome within a bound reads the clock of the port
// it calls (ADR-0023 decision 6).
//
// Every store access is under the store's monitor, so the reader handle holds
// no state of its own and is safe from any thread; the other five hold state
// of their own — staged changes, sequence counters, a served set — that is not
// synchronized, and each is driven by one thread at a time, as the Rust
// handles are `Send` and not `Sync`.
//
// Input buffers are read from their position to their limit and left as they
// were, as a Rust slice is borrowed; output buffers are written from their
// position, which is advanced past the bytes written.
package ridl.rt.loopback

import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Changed
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.CoherentSignals
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.FixedReader
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SettleError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.Wakeable
import ridl.rt.port.Watermark
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.TreeMap

/** Wakes what a store operation returned, after the monitor was left. */
private fun wake(wakers: Iterable<Waker>) {
    for (waker in wakers) waker.wake()
}

/**
 * The read-only port roles: `Attached`, `Clock`, `SignalReader`,
 * `FixedReader`, and the two signal extensions. Safe to share between
 * threads, so a face that needs only `SignalReader` can be built over it.
 */
public class ReaderHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : Clock, SignalReader, FixedReader, ScannableSignals, CoherentSignals {
    override fun now(): Timestamp = store.locked { now() }

    override fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample =
        store.locked { read(Key(iface, ord), out) }

    override fun readFixed(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): Int =
        store.locked { readFixed(Key(iface, ord), out) }

    override fun generation(iface: InterfaceNo): ULong = store.locked { generation(iface) }

    override fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int = store.locked { scan(marks, out) }

    override fun readCoherent(
        iface: InterfaceNo,
        ords: List<Ordinal>,
        out: ByteBuffer,
        samples: Array<RawSample?>,
    ): Int = store.locked { readCoherent(iface, ords, out, samples) }
}

/**
 * The `SignalWriter` port role. The staged changes and the per channel
 * sequence counters live on the handle: staging is one provider's private
 * state until its `commit`, and a sequence number is the sender's (ridl §3.1).
 */
public class WriterHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : SignalWriter {
    private val staged = TreeMap<Key, Staged>()
    private val seqs = TreeMap<Key, ULong>()

    override fun set(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        staged[Key(iface, ord)] = Staged.Set(bytes.remainingBytes())
    }

    override fun invalidate(iface: InterfaceNo, ord: Ordinal) {
        staged[Key(iface, ord)] = Staged.Invalidate
    }

    /**
     * Stages a re-affirmation only when nothing else is staged for the
     * channel: a staged `set` or `invalidate` is itself a publication, and
     * replacing it would discard what this writer staged. A later `set` or
     * `invalidate` does replace a staged touch.
     */
    override fun touch(iface: InterfaceNo, ord: Ordinal) {
        staged.putIfAbsent(Key(iface, ord), Staged.Touch)
    }

    override fun commit() {
        store.locked { commit(staged, seqs) }
    }
}

/**
 * The `EventSource` port role: one subscription set and one queue in the
 * store under this handle's identity, so each source receives its own copy of
 * every occurrence raised while it is subscribed.
 *
 * [close] removes the queue and the waiter from the store, which the Rust
 * handle does on drop; a source that is never closed keeps receiving copies
 * until the runtime is collected.
 */
public class SourceHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : EventSource, Wakeable, AutoCloseable {
    private val id: Int = store.locked { openSource() }

    override fun subscribe(iface: InterfaceNo, ords: List<Ordinal>) {
        store.locked { subscribe(id, iface, ords) }
    }

    override fun unsubscribe(iface: InterfaceNo, ords: List<Ordinal>) {
        store.locked { unsubscribe(id, iface, ords) }
    }

    override fun next(out: ByteBuffer): RawOccurrence? = store.locked { nextEvent(id, out) }

    /**
     * Stores `Interest.Event(iface)`: a `raise` that queues an occurrence of
     * `iface` for this source wakes it, and an occurrence already queued wakes
     * it at once. Every other key is not stored.
     */
    override fun wakeOn(what: Interest, waker: Waker) {
        if (what is Interest.Event) wake(store.locked { wakeOnEvent(id, what.iface, waker) })
    }

    override fun close() {
        store.locked { closeSource(id) }
    }
}

/**
 * The `EventSink` port role. One sequence counter per event channel, as ridl
 * §3.1 scopes the number: a counter per handle would show a consumer of some
 * of this sink's events a gap where nothing was lost.
 */
public class SinkHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : EventSink {
    private val seqs = TreeMap<Key, ULong>()

    override fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        val key = Key(iface, ord)
        val seq = (seqs[key] ?: 0u) + 1u
        seqs[key] = seq
        wake(store.locked { raise(key, bytes.remainingBytes(), seq) })
    }
}

/**
 * The `Caller` port role. One counter for the whole handle: on a call the
 * sequence is the caller's (ADR-0021 decision 5), which is what keeps two
 * callers on one provider from colliding.
 */
public class CallerHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : Caller, Clock, Wakeable {
    private var seq: ULong = 0u

    override fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation =
        send(CallKind.Command, iface, ord, args)

    override fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation =
        send(CallKind.Query, iface, ord, args)

    private fun send(kind: CallKind, iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation {
        seq += 1u
        val next = seq
        val (correlation, wakers) = store.locked { send(kind, Key(iface, ord), args.remainingBytes(), next) }
        wake(wakers)
        return correlation
    }

    override fun ack(c: Correlation): Result<Unit>? = store.locked { ack(c) }

    override fun reply(c: Correlation, out: ByteBuffer): Result<Int>? = store.locked { reply(c, out) }

    override fun forget(c: Correlation) {
        store.locked { forget(c) }
    }

    override fun now(): Timestamp = store.locked { now() }

    /**
     * Stores `Interest.Outcome(c)`: the settlement of `c` wakes it, and an
     * outcome already known wakes it at once. A correlation the store no
     * longer holds, never sent or forgotten, is not stored.
     *
     * Wakes `Interest.Slot` at once: nothing here is bounded, so a slot is
     * always free. Every other key is not stored.
     */
    override fun wakeOn(what: Interest, waker: Waker) {
        when (what) {
            is Interest.Outcome -> wake(store.locked { wakeOnOutcome(what.correlation, waker) })
            Interest.Slot -> waker.wake()
            is Interest.Event, is Interest.Claim -> {}
        }
    }
}

/**
 * The `Handler` port role.
 *
 * A handler that has served nothing is presented every waiting call; once it
 * has served anything, only the members it served. The empty set meaning no
 * filter is the Rust loopback's deliberate deviation from `Handler.serve`,
 * kept because the generated `dispatch` never calls `serve`. A claim belongs
 * to the handler it was presented to, so another handler's `settle` of it
 * throws [SettleError.UnknownClaim].
 *
 * [close] removes the handler's waiter from the store, which the Rust handle
 * does on drop.
 */
public class HandlerHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : Handler, Wakeable, AutoCloseable {
    private val id: Int = store.locked { openHandler() }
    private val servedKeys = LinkedHashSet<Key>()

    /** The members `serve` was called with, in the order served, with no duplicate. */
    public val served: List<Pair<InterfaceNo, Ordinal>>
        get() = servedKeys.map { it.iface to it.ord }

    override fun serve(iface: InterfaceNo, ords: List<Ordinal>) {
        ords.mapTo(servedKeys) { Key(iface, it) }
    }

    /** The filter `nextClaim` applies: `null` when this handler has served nothing and is presented every call. */
    private fun filter(): Set<Key>? = servedKeys.takeIf { it.isNotEmpty() }?.toSet()

    override fun nextClaim(out: ByteBuffer): Claim? {
        val filter = filter()
        return store.locked { nextClaim(id, filter, out) }
    }

    override fun settle(claim: ClaimId, outcome: Result<ByteBuffer>) {
        val stored = outcome.toStored()
        store.locked { settle(id, claim, stored) }?.wake()
    }

    /**
     * Stores `Interest.Claim(iface)`: a send of a call on `iface` wakes it,
     * and a call on `iface` this handler would be presented, already waiting,
     * wakes it at once. Every other key is not stored.
     */
    override fun wakeOn(what: Interest, waker: Waker) {
        if (what is Interest.Claim) {
            val filter = filter()
            wake(store.locked { wakeOnClaim(id, what.iface, filter, waker) })
        }
    }

    override fun close() {
        store.locked { closeHandler(id) }
    }
}

