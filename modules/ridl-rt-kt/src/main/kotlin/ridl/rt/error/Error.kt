// The error strata of ridl §10 that a runtime reports: the spelling of
// `ridl_rt::error` (docs/design.md §3).
package ridl.rt.error

import ridl.rt.RidlError
import ridl.rt.payload.Violation

/**
 * The outcome of a call that did not succeed. `ridl_rt::error::CallError`.
 *
 * Its two arms, [Contract] and [Transport], are themselves sealed, so a `when`
 * over a `CallError` names either the two arms or every variant of both. A
 * call outcome is carried in a Kotlin [Result], which is why this is an
 * exception.
 */
public sealed class CallError(message: String) : RidlError(message)

/**
 * A contract error, ridl §10.2. Derived from the contract and never declared.
 * `ridl_rt::error::Contract`.
 */
public sealed class Contract(message: String) : CallError(message) {
    /** `INVALID_VALUE`: a payload breaks its typl constraints. */
    public data class InvalidValue(public val violation: Violation) : Contract("INVALID_VALUE: $violation")

    /** `PRECONDITION_FAILED`: a `require` clause evaluates to false. */
    public data object PreconditionFailed : Contract("PRECONDITION_FAILED")

    /** `CONTRACT_BROKEN`: an `ensure` clause evaluates to false. */
    public data object ContractBroken : Contract("CONTRACT_BROKEN")

    /** `UNKNOWN_INTERACTION`: the peers disagree on an interface number or an ordinal. */
    public data object UnknownInteraction : Contract("UNKNOWN_INTERACTION")
}

/** An infrastructure failure that the runtime detected, ridl §10.3. `ridl_rt::error::Transport`. */
public sealed class Transport(message: String) : CallError(message) {
    /** The response bound passed without a reply. */
    public data object Timeout : Transport("the response bound passed without a reply")

    /** A command received no delivery acknowledgment within its bound. */
    public data object Undelivered : Transport("no delivery acknowledgment within the bound")

    /** The connection to the peer is lost. */
    public data object Down : Transport("the connection to the peer is lost")

    /** A payload is not a well-formed encoding. */
    public data object Corrupt : Transport("a payload is not a well-formed encoding")
}
