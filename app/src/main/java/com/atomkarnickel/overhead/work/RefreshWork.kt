package com.atomkarnickel.overhead.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.atomkarnickel.overhead.container
import com.atomkarnickel.overhead.data.RefreshOutcome
import com.atomkarnickel.overhead.widget.OverheadWidget
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Hintergrundabruf für das Widget. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val outcome = c.refresher.refresh(foreground = false)
        c.updateWidgetsNow()
        return when (outcome) {
            is RefreshOutcome.Failed -> if (runAttemptCount < 2) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}

object RefreshScheduler {
    private const val PERIODIC = "overhead-periodic"
    private const val ONCE = "overhead-once"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Sofort einmal aktualisieren (Widget-Knopf). */
    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build(),
        )
    }

    /** Periodische Aktualisierung nur, solange mindestens ein Widget existiert und ein Intervall gewählt ist. */
    suspend fun syncWithWidgets(context: Context) {
        val hasWidgets = runCatching { GlanceAppWidgetManager(context).getGlanceIds(OverheadWidget::class.java).isNotEmpty() }.getOrDefault(false)
        val minutes = context.container.settings.settings.first().widgetIntervalMinutes
        val wm = WorkManager.getInstance(context)
        if (!hasWidgets || minutes <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RefreshWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }
}
