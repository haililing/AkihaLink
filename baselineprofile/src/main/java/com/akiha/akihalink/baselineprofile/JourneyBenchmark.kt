package com.akiha.akihalink.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class JourneyBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun nodeListFirstLoad() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            seedFixture()
            startActivityAndWait(benchmarkIntent())
        },
    ) {
        device.findObject(By.desc("节点")).click()
        device.wait(Until.hasObject(By.text("Benchmark Node 001")), 5_000)
    }

    @Test
    fun nodeListScroll() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            seedFixture()
            startActivityAndWait(benchmarkIntent())
        },
    ) {
        device.findObject(By.desc("节点")).click()
        device.waitForIdle()
        repeat(3) { device.findObject(By.scrollable(true))?.fling(Direction.DOWN) }
        repeat(3) { device.findObject(By.scrollable(true))?.fling(Direction.UP) }
    }

    @Test
    fun appSideConnect() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(TraceSectionMetric("AKL/connect_total/.*", mode = TraceSectionMetric.Mode.Sum)),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            seedFixture()
            startActivityAndWait(benchmarkIntent())
        },
    ) {
        device.findObject(By.desc("启动代理")).click()
        device.wait(Until.hasObject(By.desc("关闭代理")), 5_000)
    }

    @Test
    fun nodeSwitch() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(TraceSectionMetric("AKL/node_switch/.*", mode = TraceSectionMetric.Mode.Sum)),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            seedFixture()
            startActivityAndWait(benchmarkIntent())
            device.findObject(By.desc("节点")).click()
        },
    ) {
        device.findObject(By.text("Benchmark Node 001")).click()
        device.waitForIdle()
    }
}
