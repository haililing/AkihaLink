package com.akiha.akihalink.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupProfile() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        seedFixture()
        pressHome()
        startActivityAndWait(benchmarkIntent())
    }

    @Test
    fun criticalUserJourneys() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
    ) {
        seedFixture()
        startActivityAndWait(benchmarkIntent())
        device.findObject(By.desc("节点")).click()
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
        device.findObject(By.scrollable(true))?.fling(Direction.UP)
        device.findObject(By.desc("首页")).click()
        device.findObject(By.text("全局")).click()
        device.findObject(By.desc("启动代理")).click()
        device.wait(Until.hasObject(By.desc("关闭代理")), 5_000)
        device.findObject(By.desc("关闭代理")).click()
        device.findObject(By.text("规则")).click()
        device.findObject(By.desc("启动代理")).click()
        device.wait(Until.hasObject(By.desc("关闭代理")), 5_000)
    }
}
