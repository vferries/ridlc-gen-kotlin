// The one store every handle shares, and the operations the handles call on
// it: the spelling of `crates/ridl-loopback/src/store.rs`.
//
// State two handles must agree on lives here — the signal map, the event
// queues, the call table and the clock; state that belongs to one handle alone
// lives on that handle. Every operation below runs under the store's monitor,
// reads or writes the maps and returns: no port method waits for data while
// holding it, which is what `ridl.rt.port` requires of every port method.
//
// No waker runs under the monitor either. Every operation here that would wake
// a task returns the wakers instead, and the handle wakes them after leaving
// the monitor, so a waker that reaches back into this runtime from another
// thread cannot find the monitor held by the thread that woke it. A waker is
// stored here although one handle registered it, because another handle's
// operation wakes it.
package ridl.rt.loopback

import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.port.Changed
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Correlation
import ridl.rt.port.RawOccurrence
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
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.TreeMap
import java.util.TreeSet

/** One interaction, addressed the way every port method addresses one. */
internal data class Key(val iface: InterfaceNo, val ord: Ordinal) : Comparable<Key> {
    override fun compareTo(other: Key): Int = compareValuesBy(this, other, { it.iface }, { it.ord })
}

/** A staged change, held on the writer handle until its `commit`. */
internal sealed interface Staged {
    class Set(val bytes: ByteArray) : Staged

    data object Invalidate : Staged

    data object Touch : Staged
}

internal enum class CallKind { Command, Query }

/**
 * A one-waiter slot: the waker of one task, with the key it waits under.
 * `register` and `take_if_key` of the Rust store.
 */
internal class Waiter<K> {
    private var key: K? = null
    private var waker: Waker? = null

    /**
     * Stores [waker] under [key], and returns the waker it displaced. The same
     * waker under the same key is the same task registering again, which it
     * does on every poll: it is kept and nothing is displaced. Under another
     * key the stored registration is displaced and woken even when the task
     * is the same, because the slot holds one key and the first would
     * otherwise never wake it.
     */
    fun register(key: K, waker: Waker): Waker? {
        if (this.key == key && this.waker === waker) return null
        val displaced = this.waker
        this.key = key
        this.waker = waker
        return displaced
    }

    /** Takes the waker when it waits under [key], which clears the slot. */
    fun take(key: K): Waker? {
        if (waker == null || this.key != key) return null
        return waker.also { clear() }
    }

    fun clear() {
        key = null
        waker = null
    }
}

/**
 * Everything two handles must agree on.
 *
 * The maps are sorted, as the Rust `BTreeMap`s are, so a commit applies its
 * staged changes and a scan reports its changes in one order on every run.
 */
internal class Store {
    private class SignalEntry(
        val bytes: ByteArray,
        val envelope: Envelope,
        /** `true` after `invalidate`, cleared by the next `set`. */
        val invalid: Boolean,
        /** The interface generation this entry last changed at, which [scan] selects on. */
        val changedAt: ULong,
    )

    private class QueuedEvent(val key: Key, val bytes: ByteArray, val envelope: Envelope)

    /**
     * One source handle's subscription set and its own queue. `raise` copies
     * an occurrence into the queue of every source subscribed to it at that
     * moment, so no source consumes another's.
     */
    private class SourceState {
        val subscribed = TreeSet<Key>()
        val queue = ArrayDeque<QueuedEvent>()

        /** The source's one `Interest.Event` waiter, with the interface it waits on. */
        val waiter = Waiter<InterfaceNo>()
    }

    /** A presented claim: the call it presented, and the handler holding it. */
    private class ClaimOwner(val call: Long, val handler: Int)

    /** One sent call, from the caller's send to the provider's settlement. */
    private class CallEntry(
        val kind: CallKind,
        val key: Key,
        val args: ByteArray,
        val envelope: Envelope,
    ) {
        /** `null` while unsettled; otherwise the reply bytes or the [CallError]. */
        var outcome: Result<ByteArray>? = null

        /**
         * `true` once the caller released the correlation. The entry stays:
         * a call already sent is the provider's, and is still presented and
         * settled. What changes is that `ack` and `reply` answer `null`.
         */
        var forgotten: Boolean = false

        /**
         * The call's one `Interest.Outcome` waiter. It is kept with the call
         * rather than on the caller handle, because the correlation is the key.
         */
        var waker: Waker? = null
    }

    private var now = Timestamp(0)
    private val signals = TreeMap<Key, SignalEntry>()
    private val generations = TreeMap<InterfaceNo, ULong>()
    private val fixed = TreeMap<Key, ByteArray>()
    private val sources = TreeMap<Int, SourceState>()
    private var nextSourceId = 0
    private val calls = TreeMap<Long, CallEntry>()
    private val pending = ArrayDeque<Long>()
    private var nextCallId = 0L

    /**
     * The calls presented and not yet settled, by a claim identity minted by
     * [nextClaim]: a `ClaimId` is never a call that was never presented,
     * never one already settled, and never one another handler holds.
     */
    private val claims = TreeMap<Long, ClaimOwner>()
    private var nextClaimId = 0L
    private var nextHandlerId = 0
    private var failNextSettle = false

    /**
     * Each open handler's one `Interest.Claim` waiter, with the interface it
     * waits on, from [openHandler] to [closeHandler].
     */
    private val claimWaiters = TreeMap<Int, Waiter<InterfaceNo>>()

    /** Runs [block] under the store's monitor. */
    inline fun <T> locked(block: Store.() -> T): T = synchronized(this) { block() }

    // -- the clock ----------------------------------------------------------

    fun now(): Timestamp = now

    fun advance(by: Duration) {
        require(by.micros >= 0) { "the clock advances forward: `by` is ${by.micros}" }
        val sum = now.micros + by.micros
        now = Timestamp(if (sum < now.micros) Long.MAX_VALUE else sum)
    }

    // -- signals ------------------------------------------------------------

    fun read(key: Key, out: ByteBuffer): RawSample {
        val entry = signals[key] ?: return UNPUBLISHED
        copyInto(entry.bytes, out)
        return sampleOf(entry)
    }

    /**
     * Applies one writer handle's staged changes: one generation increment
     * and one timestamp per interface touched, and one sequence number per
     * channel published, taken from the writer's own counters.
     */
    fun commit(staged: TreeMap<Key, Staged>, seqs: MutableMap<Key, ULong>) {
        val stamp = now
        // A touch of a channel with no publication re-affirms nothing. It is
        // dropped before the generation advances, so a commit of only such
        // touches changes nothing at all.
        staged.entries.removeIf { (key, op) -> op is Staged.Touch && key !in signals }
        for (iface in staged.keys.map { it.iface }.toSortedSet()) {
            generations[iface] = (generations[iface] ?: 0u) + 1u
        }
        for ((key, op) in staged) {
            val seq = (seqs[key] ?: 0u) + 1u
            seqs[key] = seq
            val previous = signals[key]
            val (bytes, invalid) = when (op) {
                is Staged.Set -> op.bytes to false
                Staged.Invalidate -> (previous?.bytes ?: ByteArray(0)) to true
                Staged.Touch -> previous!!.bytes to previous.invalid
            }
            signals[key] = SignalEntry(bytes, Envelope(stamp, seq), invalid, generations.getValue(key.iface))
        }
        staged.clear()
    }

    fun generation(iface: InterfaceNo): ULong = generations[iface] ?: 0u

    /**
     * Writes each interface's changes since its mark, in the order of
     * [marks], and updates the marks it wrote. An interface's changes go into
     * [out] all together or not at all; on the first that does not fit, the
     * scan stops with that mark untouched.
     */
    fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int {
        var written = 0
        for ((index, mark) in marks.withIndex()) {
            val current = generation(mark.iface)
            if (mark.generation >= current) continue
            val changes = signals
                .filter { (key, entry) -> key.iface == mark.iface && entry.changedAt > mark.generation }
                .map { (key, entry) -> Changed(key.iface, key.ord, entry.envelope.seq) }
            if (changes.size > out.size - written) return written
            for (change in changes) out[written++] = change
            marks[index] = mark.copy(
                generation = current,
                seq = changes.maxOfOrNull { it.seq } ?: mark.seq,
            )
        }
        return written
    }

    /** Answers every ordinal from the one state the monitor is held over: that is the coherence. */
    fun readCoherent(iface: InterfaceNo, ords: List<Ordinal>, out: ByteBuffer, samples: Array<RawSample?>): Int {
        if (samples.size < ords.size) throw ReadError.TooFewSamples(ords.size)
        val needed = ords.sumOf { signals[Key(iface, it)]?.bytes?.size ?: 0 }
        if (out.remaining() < needed) throw ReadError.Short(needed)
        var written = 0
        for ((index, ord) in ords.withIndex()) {
            val entry = signals[Key(iface, ord)]
            if (entry == null) {
                samples[index] = UNPUBLISHED
            } else {
                out.put(entry.bytes)
                written += entry.bytes.size
                samples[index] = sampleOf(entry)
            }
        }
        return written
    }

    // -- `fixed` ------------------------------------------------------------

    fun provisionFixed(key: Key, bytes: ByteArray) {
        fixed[key] = bytes
    }

    fun readFixed(key: Key, out: ByteBuffer): Int {
        val bytes = fixed[key] ?: throw ReadError.Contract(Contract.UnknownInteraction)
        copyInto(bytes, out)
        return bytes.size
    }

    // -- events -------------------------------------------------------------

    fun openSource(): Int {
        val id = nextSourceId++
        sources[id] = SourceState()
        return id
    }

    fun closeSource(id: Int) {
        sources.remove(id)
    }

    fun subscribe(id: Int, iface: InterfaceNo, ords: List<Ordinal>) {
        val state = sources[id] ?: return
        ords.mapTo(state.subscribed) { Key(iface, it) }
    }

    /** Stops delivery: the ordinals leave the set, and what is queued for them is dropped. */
    fun unsubscribe(id: Int, iface: InterfaceNo, ords: List<Ordinal>) {
        val state = sources[id] ?: return
        ords.forEach { state.subscribed.remove(Key(iface, it)) }
        state.queue.removeIf { it.key !in state.subscribed }
    }

    /**
     * Copies the occurrence into the queue of every source subscribed to it
     * now. A late subscriber receives nothing retroactive, ridl's own rule
     * for a late joiner on an event.
     */
    fun raise(key: Key, bytes: ByteArray, seq: ULong): List<Waker> {
        val envelope = Envelope(now, seq)
        val wake = mutableListOf<Waker>()
        for (state in sources.values) {
            if (key in state.subscribed) {
                state.queue.addLast(QueuedEvent(key, bytes, envelope))
                state.waiter.take(key.iface)?.let(wake::add)
            }
        }
        return wake
    }

    /**
     * Registers a source's `Interest.Event(iface)` waiter, and returns the
     * wakers to wake: the one it displaced, and the new one at once when an
     * occurrence of [iface] is already queued for this source.
     */
    fun wakeOnEvent(id: Int, iface: InterfaceNo, waker: Waker): List<Waker> {
        val state = sources[id] ?: return emptyList()
        val wake = listOfNotNull(state.waiter.register(iface, waker)).toMutableList()
        if (state.queue.any { it.key.iface == iface }) state.waiter.take(iface)?.let(wake::add)
        return wake
    }

    fun nextEvent(id: Int, out: ByteBuffer): RawOccurrence? {
        val state = sources[id] ?: return null
        val front = state.queue.peekFirst() ?: return null
        copyInto(front.bytes, out)
        state.queue.removeFirst()
        return RawOccurrence(front.key.iface, front.key.ord, front.envelope, front.bytes.size)
    }

    // -- calls --------------------------------------------------------------

    /**
     * Records a sent call, and returns its correlation and the waker of every
     * handler waiting under `Interest.Claim` for its interface. The handlers
     * are woken whatever their served sets: one that does not serve the member
     * finds nothing on its next `nextClaim` and registers again, a spurious
     * wake the contract allows.
     */
    fun send(kind: CallKind, key: Key, args: ByteArray, seq: ULong): Pair<Correlation, List<Waker>> {
        val id = nextCallId++
        calls[id] = CallEntry(kind, key, args, Envelope(now, seq))
        pending.addLast(id)
        return Correlation(id) to claimWaiters.values.mapNotNull { it.take(key.iface) }
    }

    /**
     * Registers a caller's `Interest.Outcome(c)` waiter, and returns the
     * wakers to wake: the one it displaced, and the new one at once when the
     * outcome is already known. A correlation the store no longer holds, never
     * sent or forgotten, is not stored.
     */
    fun wakeOnOutcome(c: Correlation, waker: Waker): List<Waker> {
        val entry = calls[c.value] ?: return emptyList()
        if (entry.forgotten) return emptyList()
        val wake = mutableListOf<Waker>()
        if (entry.waker !== waker) {
            entry.waker?.let(wake::add)
            entry.waker = waker
        }
        if (entry.outcome != null) {
            entry.waker?.let(wake::add)
            entry.waker = null
        }
        return wake
    }

    fun ack(c: Correlation): Result<Unit>? {
        val entry = calls[c.value] ?: return null
        // A query's correlation always answers `null` here: its outcome comes from `reply`.
        if (entry.forgotten || entry.kind != CallKind.Command) return null
        return entry.outcome?.map { }
    }

    fun reply(c: Correlation, out: ByteBuffer): Result<Int>? {
        val entry = calls[c.value] ?: return null
        if (entry.forgotten) return null
        val outcome = entry.outcome ?: return null
        return outcome.map { bytes ->
            copyInto(bytes, out)
            bytes.size
        }
    }

    /**
     * Releases the caller's interest. A settled call's entry goes; a call in
     * flight is marked forgotten and goes when its settlement lands. Either
     * way `ack` and `reply` answer `null` afterwards.
     */
    fun forget(c: Correlation) {
        val entry = calls[c.value] ?: return
        if (entry.outcome != null) {
            calls.remove(c.value)
        } else {
            // The waiter goes with the caller's interest: its settlement will
            // not be readable, so there is nothing to wake it for.
            entry.forgotten = true
            entry.waker = null
        }
    }

    fun openHandler(): Int = nextHandlerId++.also { claimWaiters[it] = Waiter() }

    fun closeHandler(handler: Int) {
        claimWaiters.remove(handler)
    }

    /**
     * Registers a handler's `Interest.Claim(iface)` waiter, and returns the
     * wakers to wake: the one it displaced, and the new one at once when a
     * call of [iface] that [served] admits is already waiting. [served] is the
     * filter `nextClaim` applies, so a call this handler would not be
     * presented does not wake it at once.
     */
    fun wakeOnClaim(handler: Int, iface: InterfaceNo, served: Collection<Key>?, waker: Waker): List<Waker> {
        val waiter = claimWaiters[handler] ?: return emptyList()
        val wake = listOfNotNull(waiter.register(iface, waker)).toMutableList()
        val waiting = pending.any { id ->
            val key = calls.getValue(id).key
            key.iface == iface && (served == null || key in served)
        }
        if (waiting) waiter.take(iface)?.let(wake::add)
        return wake
    }

    /**
     * Presents the next waiting call this handler serves: every waiting call
     * when [served] is `null`, else only those it names.
     */
    fun nextClaim(handler: Int, served: Collection<Key>?, out: ByteBuffer): Claim? {
        val id = pending.firstOrNull { served == null || calls.getValue(it).key in served } ?: return null
        val entry = calls.getValue(id)
        copyInto(entry.args, out)
        val claimId = nextClaimId++
        pending.remove(id)
        claims[claimId] = ClaimOwner(id, handler)
        // No response bound: a bound is a member's timing, and the loopback
        // has no member table to read one from.
        return Claim(ClaimId(claimId), entry.key.iface, entry.key.ord, entry.envelope, null, entry.args.size)
    }

    /**
     * Records a claim's outcome. The claim is looked up before an injected
     * failure is consumed, and an injected failure leaves the claim
     * settleable.
     */
    /** Returns the waker of the call's `Interest.Outcome` waiter, to wake once the monitor is left. */
    fun settle(handler: Int, claim: ClaimId, outcome: Result<ByteArray>): Waker? {
        val owner = claims[claim.value]
        // A claim another handler holds is unknown to this one.
        if (owner == null || owner.handler != handler) throw SettleError.UnknownClaim
        if (failNextSettle) {
            failNextSettle = false
            throw SettleError.TooLarge(0)
        }
        claims.remove(claim.value)
        val entry = calls.getValue(owner.call)
        if (entry.forgotten) {
            calls.remove(owner.call)
            return null
        }
        entry.outcome = outcome
        return entry.waker.also { entry.waker = null }
    }

    fun failNextSettle() {
        failNextSettle = true
    }

    private companion object {
        /**
         * The sample a channel with no publication answers with: the
         * consumer's binding supplies the init value, so the port reports
         * `Init` and copies nothing (ridl §4.4).
         */
        val UNPUBLISHED = RawSample(Provenance.Init, Freshness.Unbounded, Envelope(Timestamp(0), 0u), 0)

        // Every value is `Unbounded`: freshness is measured against a
        // staleness bound, and the loopback has no member table to read one from.
        fun sampleOf(entry: SignalEntry) = RawSample(
            provenance = if (entry.invalid) Provenance.Invalid(Cause.Declared) else Provenance.Live,
            freshness = Freshness.Unbounded,
            envelope = entry.envelope,
            len = entry.bytes.size,
        )

        /** Copies [bytes] into [out] from its position, or throws `Short` and writes nothing. */
        fun copyInto(bytes: ByteArray, out: ByteBuffer) {
            if (out.remaining() < bytes.size) throw ReadError.Short(bytes.size)
            out.put(bytes)
        }
    }
}

/** The bytes from [ByteBuffer.position] to its limit, without moving the caller's position. */
internal fun ByteBuffer.remainingBytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

/** A settled outcome as the store keeps it: the reply bytes, or the [CallError]. */
internal fun Result<ByteBuffer>.toStored(): Result<ByteArray> = fold(
    onSuccess = { Result.success(it.remainingBytes()) },
    onFailure = { error ->
        require(error is CallError) { "a claim is settled with a CallError, not ${error::class.qualifiedName}" }
        Result.failure(error)
    },
)
