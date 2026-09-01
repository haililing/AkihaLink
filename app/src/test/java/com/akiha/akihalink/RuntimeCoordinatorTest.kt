package com.akiha.akihalink

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeCoordinatorTest {
    @Test
    fun newerOperationInvalidatesQueuedOlderOperation() = runBlocking {
        val coordinator = RuntimeCoordinator()
        val first = coordinator.issue()
        val second = coordinator.issue()

        assertNull(coordinator.run(first) { "stale" })
        assertEquals("current", coordinator.run(second) { "current" })
    }

    @Test
    fun operationsAreSerialized() = runBlocking {
        val coordinator = RuntimeCoordinator()
        val first = coordinator.issue()
        val order = mutableListOf<Int>()

        val firstJob = async { coordinator.run(first) { order += 1; delay(20); order += 2 } }
        delay(2)
        val second = coordinator.issue()
        val secondJob = async { coordinator.run(second) { order += 3 } }
        firstJob.await()
        secondJob.await()

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun supersedingRunnerWaitsForCancelledWorkToUnwind() = runTest {
        val runner = SupersedingJobRunner()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        runner.launch(this) {
            firstStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        runner.launch(this) { secondStarted.complete(Unit) }
        runCurrent()
        assertFalse(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertTrue(secondStarted.isCompleted)
    }
}
