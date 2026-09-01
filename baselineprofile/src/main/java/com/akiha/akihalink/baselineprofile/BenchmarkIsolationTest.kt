package com.akiha.akihalink.baselineprofile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akiha.akihalink.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchmarkIsolationTest {
    @Test
    fun targetUsesBenchmarkBackendContract() {
        assertTrue(BuildConfig.BENCHMARK_MODE)
        assertTrue(BuildConfig.EDITION == "generic")
    }
}
