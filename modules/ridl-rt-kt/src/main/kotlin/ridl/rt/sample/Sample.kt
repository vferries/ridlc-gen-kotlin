// Time, the envelope, and the values a read returns (ridl §3.1, §4.5, §9):
// the spelling of `ridl_rt::sample` (docs/design.md §3).
package ridl.rt.sample

import ridl.rt.RidlError
import ridl.rt.payload.Violation

/**
 * A point in time, in microseconds since the PTP epoch, on the TAI time scale
 * (ridl §3.1). `ridl_rt::sample::Timestamp`.
 */
@JvmInline
public value class Timestamp(public val micros: Long) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int = micros.compareTo(other.micros)
}

/**
 * A length of time, in microseconds. `ridl_rt::sample::Duration`; not
 * `kotlin.time.Duration`, so generated code names it in full.
 */
@JvmInline
public value class Duration(public val micros: Long) : Comparable<Duration> {
    override fun compareTo(other: Duration): Int = micros.compareTo(other.micros)
}

/**
 * The sender's timestamp and sequence number (ridl §3.1). The runtime stamps
 * it from its clock, and no relay changes it. `ridl_rt::sample::Envelope`.
 */
public data class Envelope(
    /** When the sender published, raised or called the instance (ridl §3.1). */
    public val stamp: Timestamp,
    /** The sender's sequence number. On an event channel, a gap is a loss (ridl §3.1). */
    public val seq: ULong,
)

/** Where a signal's value comes from (ridl §4.5). `ridl_rt::sample::Provenance`. */
public sealed interface Provenance {
    /** No publication yet: the value is the init value. */
    public data object Init : Provenance

    /** The value is the latest publication. */
    public data object Live : Provenance

    /**
     * The channel is in the invalid state: the value is the last good value,
     * or the init value when there is none.
     */
    public data class Invalid(public val cause: Cause) : Provenance
}

/** Why a channel is invalid. `ridl_rt::sample::Cause`. */
public sealed interface Cause {
    /** The provider declared the invalid state (ridl §4.5). */
    public data object Declared : Cause

    /** The consumer's binding detected an invalid payload. */
    public data class Detected(public val detection: Detection) : Cause
}

/**
 * What a consumer's binding detected in a payload. `ridl_rt::sample::Detection`.
 *
 * An error, so that an [Occurrence] carries it in a Kotlin [Result] as a call
 * outcome carries a [ridl.rt.error.CallError].
 */
public sealed class Detection(message: String) : RidlError(message) {
    /** The payload breaks a typl constraint: `INVALID_VALUE`, ridl §10.2. */
    public data class InvalidValue(public val violation: Violation) : Detection("INVALID_VALUE: $violation")

    /** The payload is not a well-formed encoding: a serialization failure, ridl §10.3. */
    public data object Corrupt : Detection("the payload is not a well-formed encoding")
}

/** How old a value is, measured against its staleness bound (ridl §9). `ridl_rt::sample::Freshness`. */
public sealed interface Freshness {
    /** Within the bound. */
    public data object Fresh : Freshness

    /** Older than the bound, by [by]. */
    public data class Stale(public val by: Duration) : Freshness

    /**
     * The value has no staleness bound: its member's timing has no `max`, as
     * under `@[1s..]`. A signal with no `@` annotation is not unbounded,
     * because it receives the default range (ridl §9.1).
     */
    public data object Unbounded : Freshness
}

/** A signal value with its provenance, its freshness and its envelope. `ridl_rt::sample::Sample`. */
public data class Sample<out T>(
    /**
     * The value. Never absent: the init value under [Provenance.Init], and the
     * last good value or the init value under [Provenance.Invalid].
     */
    public val value: T,
    /** Where the value comes from. */
    public val provenance: Provenance,
    /** How old the value is. */
    public val freshness: Freshness,
    /** The sender's timestamp and sequence number. */
    public val envelope: Envelope,
) {
    /** `true` when the provenance is [Provenance.Live] and the freshness is not [Freshness.Stale]. */
    public fun usable(): Boolean = provenance == Provenance.Live && freshness !is Freshness.Stale
}

/**
 * An event occurrence as a consumer receives it. `ridl_rt::sample::Occurrence`.
 *
 * [payload] is the value, or a failure carrying the [Detection] the binding
 * made when the payload failed its check.
 */
public data class Occurrence<out T>(
    /** The payload, or what the binding detected. */
    public val payload: Result<T>,
    /** The sender's timestamp and sequence number. */
    public val envelope: Envelope,
)
