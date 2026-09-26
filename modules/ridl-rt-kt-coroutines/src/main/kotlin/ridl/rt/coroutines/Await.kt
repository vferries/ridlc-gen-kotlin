// The coroutine adapter over the polling face (docs/design.md §5, O-K3; D-K6):
// the face stays the polling one — a send returns a correlation, an outcome
// is `null` until it is known — and suspension is this one function over the
// `Wakeable` extension of ridl-rt-kt, not a second face.
package ridl.rt.coroutines

import kotlinx.coroutines.channels.Channel
import ridl.rt.port.Interest
import ridl.rt.port.Wakeable
import ridl.rt.task.Waker

/**
 * Suspends until [poll] answers a value, and returns it, woken by [port]
 * under [interest].
 *
 * [poll] is any polling read of a face, with the key its answer changes
 * under: `client.averageReply(c)` under `Interest.Outcome(c.correlation)`,
 * `client.nextEvent()` under `Interest.Event(Cabin.number)`, or a provider's
 * `Cabin.dispatch(...)` mapped to `null` when it settled nothing, under
 * `Interest.Claim(Cabin.number)`. It must not block.
 *
 * Each round registers, then reads, as the `Wakeable` contract requires, so
 * an outcome that lands between the read and the suspension still wakes it.
 * One waker serves the whole wait, so the port sees one task registering
 * again. Bound the wait with `withTimeout`: nothing here times out, as
 * nothing in a port does.
 */
public suspend fun <T : Any> await(port: Wakeable, interest: Interest, poll: () -> T?): T {
    val woken = Channel<Unit>(Channel.CONFLATED)
    val waker = Waker { woken.trySend(Unit) }
    while (true) {
        port.wakeOn(interest, waker)
        poll()?.let { return it }
        woken.receive()
    }
}
