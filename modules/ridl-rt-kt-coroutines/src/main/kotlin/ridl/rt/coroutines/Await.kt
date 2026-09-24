// The coroutine adapter over the polling face (docs/design.md §5, O-K3; D-K6):
// the face stays the polling one — a send returns a correlation, an outcome
// is `null` until it is known — and suspension is this one function over the
// `Wakeable` extension of ridl-rt-kt, not a second face.
package ridl.rt.coroutines

import kotlinx.coroutines.channels.Channel
import ridl.rt.port.Wakeable

/**
 * Suspends until [poll] answers a value, and returns it.
 *
 * [poll] is any polling read of a face: `client.averageReply(c)`,
 * `client.setLevelAck(c)`, `client.nextEvent()`, or a provider's
 * `Cabin.dispatch(...)` mapped to `null` when it settled nothing. It is
 * called once at once, then again after every change [port] reports, until it
 * answers something other than `null`. It must not block.
 *
 * The wake-up is registered before the second poll, so an outcome that
 * arrives between the first poll and the registration is still seen. The
 * registration is closed when this returns, throws or is cancelled. Bound the
 * wait with `withTimeout`: nothing here times out, as nothing in a port does.
 */
public suspend fun <T : Any> await(port: Wakeable, poll: () -> T?): T {
    poll()?.let { return it }
    val changed = Channel<Unit>(Channel.CONFLATED)
    port.onChange { changed.trySend(Unit) }.use {
        while (true) {
            poll()?.let { return it }
            changed.receive()
        }
    }
}
