package app.ferry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.Notification
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

object Sync {
    // ponytail: arrivals are polled every 15 min in the background (WorkManager's floor); add FCM push for instant delivery
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("arrivals",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ArrivalsWorker>(15, TimeUnit.MINUTES).setConstraints(online).build())
    }

    fun now(ctx: Context) {
        // APPEND_OR_REPLACE: a new arrival always gets a run, even if an earlier one is mid-run or stuck in backoff.
        WorkManager.getInstance(ctx).enqueueUniqueWork("arrivals-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ArrivalsWorker>().setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }

    /** Uploads files already copied into the app's cache, so the share grant can expire safely. */
    fun ferry(ctx: Context, files: List<File>, to: List<String>) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<DeparturesWorker>().setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf("files" to files.map { it.path }.toTypedArray(), "to" to to.toTypedArray()))
                .build())
    }

    fun stop(ctx: Context) = WorkManager.getInstance(ctx).cancelAllWork()
}

/** Downloads every pending arrival into the harbor folder and acks it. */
class ArrivalsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.docked) return Result.success()
        val api = Api(prefs)
        val pending = try {
            api.crossings(out = false).filter { it.status == "pending" }
        } catch (e: ApiError) {
            return if (e.code == 401) Result.success() else Result.retry()
        } catch (e: Exception) {
            return Result.retry()
        }
        // One bad arrival must not hold back the rest: try each, retry later only for the ones that can still land.
        var retry = false
        for (c in pending) {
            try {
                notifyLanded(applicationContext, c, Harbor.land(applicationContext, prefs, api, c), Harbor.label(prefs))
            } catch (e: ApiError) {
                if (e.code == 401) return Result.success()
                if (e.code >= 500) retry = true // 404/410: gone from the server, nothing to retry
            } catch (e: Exception) {
                retry = true
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, "Bringing files ashore")
}

class DeparturesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val files = inputData.getStringArray("files").orEmpty().map(::File)
        val to = inputData.getStringArray("to").orEmpty().toList()
        if (!prefs.docked) return Result.failure()
        val api = Api(prefs)
        for (f in files.filter { it.exists() }) {
            try {
                f.inputStream().use { api.upload(f.name, f.length(), to, it) {} }
                f.delete()
                f.parentFile?.delete()
            } catch (e: ApiError) {
                if (e.code in 500..599) return Result.retry()
                f.delete() // the server rejected it (too big, bad destination): retrying will not help
                f.parentFile?.delete()
            } catch (e: Exception) {
                return Result.retry() // network dropped: resend the files that are left
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, "Ferrying files")
}

private const val CHANNEL = "crossings"

private fun channel(ctx: Context): NotificationManager {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Crossings", NotificationManager.IMPORTANCE_DEFAULT))
    return nm
}

// Only used below Android 12, where expedited work runs as a foreground service.
private fun foregroundInfo(ctx: Context, title: String): ForegroundInfo {
    channel(ctx)
    val n = Notification.Builder(ctx, CHANNEL).setContentTitle(title).setSmallIcon(R.drawable.ic_stat).setOngoing(true).build()
    return ForegroundInfo(title.hashCode(), n)
}

private fun notifyLanded(ctx: Context, c: Crossing, uri: Uri, where: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    val pi = PendingIntent.getActivity(ctx, c.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE)
    channel(ctx).notify(c.id.hashCode(), Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("${c.name} landed")
        .setContentText("From ${c.from} · in $where")
        .setContentIntent(pi)
        .setAutoCancel(true)
        .build())
}
