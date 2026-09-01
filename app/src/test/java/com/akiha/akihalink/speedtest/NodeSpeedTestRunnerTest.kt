package com.akiha.akihalink.speedtest

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSpeedTestRunnerTest {
    @Test
    fun rejectsNonPositiveConcurrency() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeSpeedTestRunner(probe = { null }, concurrency = 0)
        }
    }

    @Test
    fun limitsConcurrencyToTwentyAndClassifiesTwoSamples() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val results = ConcurrentHashMap<String, NodeLatencyResult>()
        val targets = (0 until 45).map { SpeedTestTarget("id-$it", "tag-$it") } +
            listOf(
                SpeedTestTarget("stable", "stable"),
                SpeedTestTarget("unstable", "unstable"),
                SpeedTestTarget("down", "down"),
            )
        val runner = NodeSpeedTestRunner(
            probe = { tag ->
                val now = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    delay(15)
                    val call = calls.computeIfAbsent(tag) { AtomicInteger() }.incrementAndGet()
                    when (tag) {
                        "stable" -> if (call == 1) 120 else 80
                        "unstable" -> if (call == 1) null else 210
                        "down" -> null
                        else -> 100
                    }
                } finally {
                    active.decrementAndGet()
                }
            },
            clock = { 1234L },
        )

        runner.run(targets) { target, result -> results[target.nodeId] = result }

        assertEquals(20, maximum.get())
        assertEquals(targets.size, results.size)
        assertEquals(NodeLatencyResult(NodeLatencyStatus.AVAILABLE, 80, 1234L, listOf(120, 80)), results["stable"])
        assertEquals(NodeLatencyResult(NodeLatencyStatus.UNSTABLE, 210, 1234L, listOf(210)), results["unstable"])
        assertEquals(NodeLatencyResult(NodeLatencyStatus.UNAVAILABLE, null, 1234L), results["down"])
        assertTrue(calls.values.all { it.get() == 2 })
    }

    @Test
    fun cancellationStopsPendingWorkersWithoutPublishingResults() = runBlocking {
        val started = AtomicInteger()
        val published = AtomicInteger()
        val runner = NodeSpeedTestRunner(
            probe = {
                started.incrementAndGet()
                awaitCancellation()
            },
        )
        val job = launch {
            runner.run((0 until 50).map { SpeedTestTarget("id-$it", "tag-$it") }) { _, _ ->
                published.incrementAndGet()
            }
        }
        while (started.get() < 20) delay(1)

        job.cancelAndJoin()

        assertEquals(20, started.get())
        assertEquals(0, published.get())
    }
}
