package de.universam.victron.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * Emits at most one value per [windowMillis], always the newest one, and never swallows the last.
 *
 * Leading edge: the first value goes straight through, then the window is held open while newer
 * values collapse onto each other, and the survivor goes out the moment it closes. A value is
 * therefore delayed by at most [windowMillis] and none is lost, only superseded.
 *
 * Deliberately not [kotlinx.coroutines.flow.sample]: that starts a ticker coroutine which keeps
 * waking for as long as the flow is collected, whether or not anything is arriving. These flows
 * live for as long as a screen is on, so an idle timer would cost more than the throttle saves.
 * Here an idle upstream costs one suspended channel receive and nothing else.
 */
public fun <T> Flow<T>.throttleLatest(windowMillis: Long): Flow<T> {
    require(windowMillis > 0) { "windowMillis must be positive, was $windowMillis" }
    return flow {
        conflate().collect { value ->
            emit(value)
            // Values arriving during the wait land in the single-slot conflated buffer and
            // overwrite each other, so the next iteration resumes with the newest reading
            // rather than working through a backlog of stale ones.
            delay(windowMillis)
        }
    }
}
