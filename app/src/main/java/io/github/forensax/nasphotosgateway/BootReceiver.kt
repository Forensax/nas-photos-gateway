package io.github.forensax.nasphotosgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        // Credential-encrypted storage becomes available after first unlock.
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("boot-restore", ExistingWorkPolicy.KEEP, request)
    }
}

class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val gateway = (applicationContext as GatewayApp).gateway
        val enabled = runCatching { gateway.loadConfig().restoreAtBoot }.getOrDefault(false)
        if (!enabled) return Result.success()
        val success = gateway.execute("restore")
        return if (success) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
