package com.akiha.akihalink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akiha.akihalink.config.AndroidUidPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED) &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) return
        val changedUid = intent.getIntExtra(Intent.EXTRA_UID, -1)
        if (changedUid < 10_000 || AndroidUidPolicy.isIsolatedUid(changedUid)) return
        val changedPackage = intent.data?.schemeSpecificPart.orEmpty()
        val applicationContext = context.applicationContext
        val reconcileAll = changedUid == applicationContext.applicationInfo.uid
        try {
            PackageReconciliationStore(applicationContext).enqueue(changedUid, changedPackage, reconcileAll)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to persist package reconciliation", error)
            return
        }
        val pendingResult = goAsync()
        val reconciliation = PROCESS_SCOPE.launch {
            try {
                RuntimeOperationGate.mutex.withLock {
                    drainPendingPackageChanges(applicationContext)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Package identity reconciliation failed", error)
            }
        }
        PROCESS_SCOPE.launch {
            try {
                withTimeout(BROADCAST_COMPLETION_TIMEOUT_MILLIS) {
                    reconciliation.join()
                }
            } catch (_: TimeoutCancellationException) {
                // Work can safely continue because the event was durably queued before goAsync().
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AkihaLinkPackages"
        const val BROADCAST_COMPLETION_TIMEOUT_MILLIS = 8_000L
        val PROCESS_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
