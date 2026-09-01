package com.akiha.akihalink.speedtest

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NodeLatencyStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("unstable") UNSTABLE,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class NodeLatencyResult(
    val status: NodeLatencyStatus,
    val delayMs: Int? = null,
    val testedAt: Long,
    val samplesMs: List<Int> = emptyList(),
)

data class SpeedTestTarget(
    val nodeId: String,
    val nodeTag: String,
)

class NodeSpeedTestRunner(
    private val probe: suspend (String) -> Int?,
    private val concurrency: Int = CONCURRENCY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(concurrency > 0) { "Concurrency must be positive" }
    }

    suspend fun run(
        targets: List<SpeedTestTarget>,
        onResult: suspend (SpeedTestTarget, NodeLatencyResult) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency)
        targets.map { target ->
            async {
                val result = semaphore.withPermit {
                    val samples = listOf(probe(target.nodeTag), probe(target.nodeTag)).filterNotNull()
                    when (samples.size) {
                        2 -> NodeLatencyResult(NodeLatencyStatus.AVAILABLE, samples.minOrNull()!!, clock(), samples)
                        1 -> NodeLatencyResult(NodeLatencyStatus.UNSTABLE, samples.single(), clock(), samples)
                        else -> NodeLatencyResult(NodeLatencyStatus.UNAVAILABLE, testedAt = clock(), samplesMs = emptyList())
                    }
                }
                onResult(target, result)
            }
        }.awaitAll()
    }

    companion object {
        const val CONCURRENCY = 20
    }
}
