package com.akiha.akihalink.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope

const val PACKAGE_NAME = "com.akiha.akihalink"
private const val SEED_EXTRA = "com.akiha.akihalink.BENCHMARK_SEED"

fun MacrobenchmarkScope.seedFixture() {
    val command = "am start -W -n $PACKAGE_NAME/.MainActivity --ez $SEED_EXTRA true"
    device.executeShellCommand(command)
    device.waitForIdle()
    device.executeShellCommand("am force-stop $PACKAGE_NAME")
}

fun benchmarkIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
    setPackage(PACKAGE_NAME)
    addCategory(Intent.CATEGORY_LAUNCHER)
}
