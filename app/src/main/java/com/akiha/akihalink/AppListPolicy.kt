package com.akiha.akihalink

enum class AppTypeFilter {
    ALL,
    USER,
    SYSTEM,
}

internal fun filterInstalledApps(
    apps: List<InstalledApp>,
    filter: AppTypeFilter,
    query: String,
): List<InstalledApp> = apps.filter { app ->
    val typeMatches = when (filter) {
        AppTypeFilter.ALL -> true
        AppTypeFilter.USER -> !app.isSystem
        AppTypeFilter.SYSTEM -> app.isSystem
    }
    typeMatches && (
        query.isBlank() || app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
        )
}

internal fun sortInstalledApps(apps: List<InstalledApp>): List<InstalledApp> =
    apps.sortedWith(
        compareByDescending<InstalledApp> { it.excluded }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.packageName },
    )
