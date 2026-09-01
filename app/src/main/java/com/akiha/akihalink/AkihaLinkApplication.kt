package com.akiha.akihalink

import android.app.Application
import com.topjohnwu.superuser.Shell

class AkihaLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(120),
        )
    }
}
