package de.universam.victron.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The throttle is what keeps a burst of advertisements from turning into a burst of gauge redraws,
 * so its two promises are worth pinning down: at most one value per window, and the newest value
 * always arrives eventually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleLatestTest {

    @Test
    fun `first value passes straight through`() = runTest {
        val seen = mutableListOf<Int>()
        val job = launch { flowOf(1).throttleLatest(500).collect { seen += it } }
        runCurrent()

        assertEquals(listOf(1), seen, "the leading value must not wait for the window")
        job.cancel()
    }

    @Test
    fun `a burst inside one window collapses to its newest value`() = runTest {
        val upstream = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val seen = mutableListOf<Int>()
        val job = launch { upstream.throttleLatest(500).collect { seen += it } }
        runCurrent()

        // 1 goes out immediately and opens the window.
        upstream.emit(1)
        runCurrent()
        assertEquals(listOf(1), seen, "the leading value opens the window")

        // 2..5 all land inside that window.
        listOf(2, 3, 4, 5).forEach { upstream.emit(it) }
        runCurrent()
        assertEquals(listOf(1), seen, "nothing may escape an open window")

        advanceTimeBy(501)
        assertEquals(listOf(1, 5), seen, "the window must close on the newest value, not on 2")
        job.cancel()
    }

    @Test
    fun `emissions are at most one per window`() = runTest {
        val seen = mutableListOf<Int>()
        // 100 values, 10 ms apart: 1 s of upstream at 100 Hz.
        val upstream = flow {
            repeat(100) {
                emit(it)
                delay(10)
            }
        }
        upstream.throttleLatest(500).toList(seen)

        // 1 s of input at a 500 ms window: the leading value plus one per closed window.
        assertEquals(3, seen.size, "expected ~2 Hz, got ${seen.size} emissions: $seen")
        assertEquals(0, seen.first(), "the first value should be the leading one")
    }

    @Test
    fun `a quiet upstream is not woken by the throttle`() = runTest {
        val upstream = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val seen = mutableListOf<Int>()
        val job = launch { upstream.throttleLatest(500).collect { seen += it } }
        runCurrent()

        upstream.emit(1)
        runCurrent()
        // Ten windows' worth of silence must not produce anything: no ticker, no repeat emissions.
        advanceTimeBy(5_000)

        assertEquals(listOf(1), seen, "an idle throttle must stay silent")
        job.cancel()
    }

    @Test
    fun `the last value of a burst is never lost`() = runTest {
        val upstream = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val seen = mutableListOf<Int>()
        val job = launch { upstream.throttleLatest(500).collect { seen += it } }
        runCurrent()

        upstream.emit(1)
        runCurrent()
        // 42 arrives just inside the window and nothing follows it — it must still show up.
        advanceTimeBy(100)
        upstream.emit(42)
        advanceTimeBy(5_000)

        assertEquals(listOf(1, 42), seen, "a trailing value must be delivered, only delayed")
        job.cancel()
    }

    @Test
    fun `a non-positive window is rejected`() {
        assertThrows<IllegalArgumentException> { flowOf(1).throttleLatest(0) }
        assertThrows<IllegalArgumentException> { flowOf(1).throttleLatest(-1) }
    }
}
