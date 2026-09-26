// `ridl-rt-kt-conformance`, the port contract tests generic over a runtime:
// the spelling of `crates/ridl-rt-conformance/src/lib.rs` (story E11.20, the
// first half, at ridl `main` 83214a1).
//
// Each public test method of the contract classes here is one test of what
// `ridl.rt.port` states a runtime does behind a port. They are generic over a
// [Factory], the one thing a runtime writes to run them. A runtime runs the
// whole suite from its own tests with [suite], and the tests of the signal
// extensions it implements with [scannableSuite] and [coherentSuite]:
//
//     @TestFactory fun ports() = suite(MyFactory)
//     @TestFactory fun scannable() = scannableSuite(MyFactory)
//
// What the suite leaves out is the crate documentation's list, unchanged: what
// the port contract leaves to a runtime (where the clock starts, what `advance`
// does with a negative duration, which error the injected fault reports beyond
// not being `UnknownClaim`); a handler that has served nothing; two event sinks
// on one event channel; `FixedReader`; anything a runtime reports from a
// catalog descriptor; the threading model; and a runtime's own API beyond the
// factory.
package ridl.rt.conformance

import org.junit.jupiter.api.DynamicTest
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.CoherentSignals
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import java.nio.ByteBuffer
import kotlin.reflect.KFunction0

/**
 * What the suite needs from a runtime beyond its port interfaces:
 * `ridl_rt_conformance::Factory`.
 *
 * [R] is the runtime the suite drives: one value implementing every port the
 * suite calls. Under ADR-0021 decision 12 that is an aggregate handle, one the
 * runtime offers or one its tests write over the role handles. The two signal
 * extensions are not in this bound, because a runtime may omit them; their
 * suites ask for them.
 *
 * Every runtime a factory builds is independent of every other.
 */
public interface Factory<R>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    /**
     * A new runtime attached to [catalog], with nothing published, raised or
     * sent. Its clock moves only when [advance] moves it: it never reads
     * wall-clock time. Where it starts is the runtime's choice.
     */
    public fun runtime(catalog: CatalogRef): R

    /**
     * A new event source on [runtime], attached to the same catalog, with its
     * own subscriptions and its own queue. An occurrence raised through
     * [runtime] reaches it once it subscribes.
     */
    public fun source(runtime: R): EventSource

    /**
     * A new caller on [runtime], attached to the same catalog, with its own
     * sequence counter. Its calls are presented to the handlers of [runtime].
     */
    public fun caller(runtime: R): Caller

    /**
     * A new handler on [runtime], attached to the same catalog, with its own
     * served set and its own claims. It is presented the calls to the members
     * it serves.
     */
    public fun handler(runtime: R): Handler

    /**
     * Advances the clock of [runtime] by [by], which the suite never passes
     * negative. After it, `Clock.now` reads exactly [by] later than before.
     */
    public fun advance(runtime: R, by: Duration)

    /**
     * Makes the next `Handler.settle` through [runtime] of a claim [runtime]
     * holds fail, with an error other than `SettleError.UnknownClaim`, and
     * record no outcome. A `settle` of a claim [runtime] does not hold still
     * fails with `UnknownClaim`, and does not spend the fault. The fault is
     * injected once: the settlement after the failed one succeeds.
     */
    public fun failNextSettle(runtime: R)
}

/**
 * The tests of one module of the suite, over one factory. Each public test
 * method is one contract case; [tests] lists every one of them, and a test of
 * this module checks that it misses none.
 */
public abstract class Contract<R>(protected val factory: Factory<R>)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    /** Every test method of this contract, in the order the Rust module declares them. */
    public abstract val tests: List<KFunction0<Unit>>

    /** A new runtime attached to [catalog]. */
    protected fun runtime(): R = factory.runtime(catalog)

    /** One dynamic test per entry of [tests], named after the method it runs. */
    internal fun dynamicTests(): List<DynamicTest> = tests.map { test -> DynamicTest.dynamicTest(test.name) { test() } }

    protected companion object {
        public val IFACE: InterfaceNo = InterfaceNo(1u)
        public val ORD: Ordinal = Ordinal(1u)
        public val OTHER: Ordinal = Ordinal(2u)

        /**
         * The catalog every test attaches to. The hash is all zeros, the
         * placeholder the descriptor emitter writes until story E16.2
         * computes a real one.
         */
        public val catalog: CatalogRef = CatalogRef("face.demo", CatalogHash(ByteArray(32)))

        /** [start] moved forward by [by] microseconds. */
        public fun later(start: Timestamp, by: Long): Timestamp = Timestamp(start.micros + by)

        public fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

        public fun out(size: Int): ByteBuffer = ByteBuffer.allocate(size)

        /** The bytes a port wrote into this buffer: from 0 to its position. */
        public fun ByteBuffer.written(): ByteArray = ByteArray(position()).also { duplicate().flip().get(it) }

        public fun array(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}

/**
 * The tests of the ports every runtime presents, over [factory]: the base arm
 * of `ridl_rt_conformance::suite!`.
 */
public fun <R> suite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler =
    listOf(
        AttachedContract(factory),
        ClockContract(factory),
        SignalsContract(factory),
        EventsContract(factory),
        CallsContract(factory),
    ).flatMap { it.dynamicTests() }

/** The tests of the `ScannableSignals` extension: `suite!(F; scannable)`. */
public fun <R> scannableSuite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : ScannableSignals =
    ScannableContract(factory).dynamicTests()

/** The tests of the `CoherentSignals` extension: `suite!(F; coherent)`. */
public fun <R> coherentSuite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : CoherentSignals =
    CoherentContract(factory).dynamicTests()
