package com.akiha.akihalink

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListPolicyTest {
    private val userApp = InstalledApp(10_101, "dev.example.user", "User", excluded = false)
    private val systemApp = InstalledApp(
        10_102,
        "dev.example.system",
        "System",
        excluded = false,
        isSystem = true,
    )

    @Test
    fun filtersAllUserAndSystemApps() {
        val apps = listOf(userApp, systemApp)

        assertEquals(apps, filterInstalledApps(apps, AppTypeFilter.ALL, ""))
        assertEquals(listOf(userApp), filterInstalledApps(apps, AppTypeFilter.USER, ""))
        assertEquals(listOf(systemApp), filterInstalledApps(apps, AppTypeFilter.SYSTEM, ""))
    }

    @Test
    fun searchWorksInsideSelectedType() {
        val apps = listOf(userApp, systemApp)

        assertEquals(
            listOf(systemApp),
            filterInstalledApps(apps, AppTypeFilter.SYSTEM, "example.system"),
        )
        assertEquals(emptyList<InstalledApp>(), filterInstalledApps(apps, AppTypeFilter.USER, "System"))
    }

    @Test
    fun onlyPersistedExclusionsSortFirst() {
        val confirmed = userApp.copy(excluded = true)
        val result = sortInstalledApps(listOf(systemApp, confirmed))

        assertEquals(listOf(confirmed, systemApp), result)
    }
}
