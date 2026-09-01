package com.akiha.akihalink

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide gate shared by UI operations and broadcast reconciliation. */
internal object RuntimeOperationGate {
    val mutex = Mutex()
}

/** Runs only the newest submitted job and waits for a superseded job to unwind first. */
internal class SupersedingJobRunner {
    private val generation = AtomicLong(0)
    @Volatile private var active: Job? = null

    fun launch(scope: CoroutineScope, block: suspend (Long) -> Unit): Job {
        val token: Long
        val previous: Job?
        val job: Job
        synchronized(this) {
            token = generation.incrementAndGet()
            previous = active
            job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.cancelAndJoin()
                if (isCurrent(token)) block(token)
            }
            active = job
            job.invokeOnCompletion {
                synchronized(this) {
                    if (active === job) active = null
                }
            }
            previous?.cancel()
            job.start()
        }
        return job
    }

    fun cancel(): Job? = synchronized(this) {
        generation.incrementAndGet()
        active?.also { it.cancel() }
    }

    suspend fun cancelAndJoin() {
        cancel()?.join()
    }

    fun isCurrent(token: Long): Boolean = generation.get() == token
}

/** Serializes runtime mutations and identifies the newest requested operation. */
internal class RuntimeCoordinator {
    private val mutex = Mutex()
    private val generation = AtomicLong(0)

    fun issue(): Long = generation.incrementAndGet()

    fun isCurrent(operation: Long): Boolean = generation.get() == operation

    suspend fun <T> run(operation: Long, block: suspend () -> T): T? = mutex.withLock {
        if (!isCurrent(operation)) return@withLock null
        block()
    }
}
