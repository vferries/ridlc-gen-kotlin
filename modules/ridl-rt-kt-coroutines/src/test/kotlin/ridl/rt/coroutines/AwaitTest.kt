package ridl.rt.coroutines

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ridl.rt.port.Wakeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A [Wakeable] a test drives by hand, which counts the callbacks registered and still open. */
private class Hand : Wakeable {
    val callbacks = CopyOnWriteArrayList<() -> Unit>()

    override fun onChange(callback: () -> Unit): AutoCloseable {
        callbacks += callback
        return AutoCloseable { callbacks.remove(callback) }
    }

    fun change() = callbacks.forEach { it() }
}

class AwaitTest {
    @Test
    fun `a value already known is returned without registering`() = runBlocking {
        val hand = Hand()
        assertEquals(7, await(hand) { 7 })
        assertEquals(0, hand.callbacks.size)
    }

    @Test
    fun `it suspends until a change makes the poll answer`() = runBlocking {
        val hand = Hand()
        val value = AtomicReference<Int?>(null)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { await(hand) { value.get() } }
        assertEquals(1, hand.callbacks.size, "it registered and is waiting")
        hand.change()
        yield()
        assertEquals(1, hand.callbacks.size, "a change with nothing new keeps it waiting")
        value.set(3)
        hand.change()
        assertEquals(3, waiting.await())
        assertEquals(0, hand.callbacks.size, "the registration is closed on return")
    }

    @Test
    fun `an outcome that lands between the first poll and the registration is not lost`() = runBlocking {
        val hand = Hand()
        val polls = AtomicInteger()
        // The first poll finds nothing; the outcome is known by the second, with no change reported.
        val result = withTimeout(1_000) { await(hand) { if (polls.incrementAndGet() >= 2) "late" else null } }
        assertEquals("late", result)
    }

    @Test
    fun `a cancelled wait closes its registration`() = runBlocking {
        val hand = Hand()
        assertNull(withTimeoutOrNull(50) { await<Int>(hand) { null } })
        assertEquals(0, hand.callbacks.size)
    }

    @Test
    fun `a change reported on another thread wakes it`() = runBlocking {
        val hand = Hand()
        val value = AtomicReference<String?>(null)
        val waiting = async(Dispatchers.Default) { await(hand) { value.get() } }
        while (hand.callbacks.isEmpty()) yield()
        Thread {
            value.set("from another thread")
            hand.change()
        }.start()
        assertEquals("from another thread", withTimeout(5_000) { waiting.await() })
    }
}
