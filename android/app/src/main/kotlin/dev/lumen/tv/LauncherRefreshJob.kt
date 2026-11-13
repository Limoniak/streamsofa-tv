package dev.lumen.tv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import dev.lumen.data.CatalogRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val JOB_ID = 1
private const val PERIOD_MS = 30 * 60_000L

/** Relit les suivis en live toutes les 30 min, app fermée : la rangée du launcher ne reste pas figée. */
@AndroidEntryPoint
class LauncherRefreshJob : JobService() {
    @Inject lateinit var catalog: CatalogRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            // Sans compte, followedStreams vide la rangée puis lève NotSignedIn : rien à faire de plus.
            runCatching { catalog.followedStreams() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // Déjà planifiée : la replanifier repousserait son prochain passage.
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, LauncherRefreshJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }
    }
}
