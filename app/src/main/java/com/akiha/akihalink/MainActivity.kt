package com.akiha.akihalink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.BENCHMARK_MODE && intent.getBooleanExtra(BENCHMARK_SEED_EXTRA, false)) {
            Class.forName("com.akiha.akihalink.benchmark.BenchmarkBootstrap")
                .getMethod("seed", android.content.Context::class.java)
                .invoke(null, applicationContext)
        }
        enableEdgeToEdge()
        setContent {
            AkihaLinkApp(mainViewModel)
        }
    }

    override fun onStop() {
        mainViewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.onAppForegrounded()
    }

    private companion object {
        const val BENCHMARK_SEED_EXTRA = "com.akiha.akihalink.BENCHMARK_SEED"
    }
}
