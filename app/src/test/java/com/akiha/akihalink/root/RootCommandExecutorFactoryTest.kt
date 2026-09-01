package com.akiha.akihalink.root

import com.akiha.akihalink.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandExecutorFactoryTest {
    @Test
    fun fakeBackendExistsOnlyInBenchmarkBuild() {
        val executor = RootCommandExecutorFactory.create()
        assertEquals(
            BuildConfig.BENCHMARK_MODE,
            executor === BenchmarkRootCommandExecutor,
        )
    }
}
