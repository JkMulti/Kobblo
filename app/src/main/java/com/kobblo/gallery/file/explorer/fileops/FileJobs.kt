package com.kobblo.gallery.file.explorer.fileops

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a job killed partway did, to say on the next launch. */
data class JobNotice(val id: Long, val title: String, val body: String, val folder: String?)

/**
 * Long file work, kept out of reach of any screen's lifetime.
 *
 * The work itself goes through [JobRunner], which belongs to the process, so leaving the app, rotating or backing
 * out doesn't touch it. A foreground service is only what tells Android not to kill the process meanwhile: it's
 * asked for once a job has run long enough to matter or as soon as the person leaves, so a quick paste never
 * costs a service or flashes a notification.
 *
 * Why a foreground service of type dataSync and not a user-initiated data transfer job: those need Android 14 and
 * minSdk is 26, so this would be needed anyway, and they must name a network, which local file work has no use for.
 * Android 15's limit of 6 hours a day is met in [onSystemLimit].
 */
object FileJobs {
    private const val TAG = "FileJobs"
    private const val CHANNEL_ID = "file_jobs"
    private const val OLD_EXTRACT_CHANNEL_ID = "archive_extract_channel"
    internal const val PROGRESS_ID = 3001
    private const val RESULT_ID_BASE = 3100
    private const val RESULT_IDS = 8

    /** A quick job shouldn't flash anything. This is about what "quick" feels like. */
    private const val PROMOTE_AFTER_MILLIS = 800L
    private const val NOTIFY_EVERY_MILLIS = 1000L
    private const val PREFS = "file_jobs"
    private const val PREF_NOTICES = "notices"

    private lateinit var app: Application
    private lateinit var runner: JobRunner
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _notices = MutableStateFlow<List<JobNotice>>(emptyList())
    /** What the last process couldn't finish, until someone has read it. */
    val notices: StateFlow<List<JobNotice>> = _notices.asStateFlow()

    val state: StateFlow<JobsState> get() = runner.state
    val finished: SharedFlow<FinishedJob> get() = runner.finished

    private var startedActivities = 0
    private var foreground = false

    /** Past the point where a notification is worth having. */
    private var showing = false
    private var notifiedAt = 0L
    private var notifiedKey: String? = null
    private var limitHit = false
    private var channelMade = false

    val isAppVisible: Boolean get() = startedActivities > 0

    private val notifications: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Application start. Nothing here may be slow, because this runs for every way into the process. */
    fun init(application: Application) {
        app = application
        val journals = File(application.noBackupFilesDir, "file_jobs")
        runner = JobRunner(scope, journals)
        application.registerActivityLifecycleCallbacks(lifecycle)
        scope.launch(Dispatchers.IO) {
            createChannel()
            loadNotices()
            try {
                recover(journals)
            } catch (e: Exception) {
                // However this goes, new work has to be able to start.
                Log.e(TAG, "Couldn't settle what an earlier run left", e)
            }
            runner.openForWork()
        }
        watch()
    }

    internal fun enqueue(spec: JobSpec): Long {
        limitHit = false
        return runner.enqueue(spec)
    }

    /** Says so when this has to wait its turn, since otherwise nothing would seem to happen. */
    internal fun enqueueAndSay(spec: JobSpec, say: (String) -> Unit): Long {
        val wasBusy = state.value.busy
        val id = enqueue(spec)
        if (wasBusy) say(app.getString(R.string.safety_job_waiting))
        return id
    }

    fun cancel(id: Long) = runner.cancel(id)

    fun cancelRunning() = runner.cancelRunning()

    fun cancelAll() = runner.cancelAll()

    fun togglePause() = runner.togglePause()

    fun isBusyWith(path: String): Boolean = runner.isBusyWith(path)

    fun dismissNotice(id: Long) {
        _notices.update { current -> current.filterNot { it.id == id } }
        saveNotices()
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            // Refused from the background earlier, or over the day's limit, which coming back resets.
            if (state.value.busy && showing && !foreground) promote()
        }

        // The person is leaving, and this is the last moment the system is sure to allow a foreground service.
        override fun onActivityPaused(activity: Activity) {
            if (state.value.busy) promote()
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun watch() {
        scope.launch(Dispatchers.Main) {
            runner.state.map { it.busy }.distinctUntilChanged().collectLatest { busy ->
                if (busy) {
                    delay(PROMOTE_AFTER_MILLIS)
                    promote()
                } else {
                    showing = false
                    notifiedKey = null
                    notifications.cancel(PROGRESS_ID)
                }
            }
        }
        scope.launch(Dispatchers.Main) {
            runner.state.collect { jobs -> if (showing && jobs.running != null) updateProgress(jobs) }
        }
        scope.launch(Dispatchers.Main) {
            runner.finished.collect { job ->
                // Someone in the app has the pill or the bar, and the message that follows it.
                if (!isAppVisible) showResult(job)
            }
        }
    }

    @MainThread
    private fun promote() {
        showing = true
        if (foreground) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, FileJobService::class.java))
        } catch (e: Exception) {
            // Started from the background, or over the limit. The work goes on regardless, and shows
            // a plain notification until the service can start.
            Log.w(TAG, "Couldn't start the foreground service", e)
            updateProgress(state.value)
        }
    }

    @MainThread
    internal fun onForegroundChanged(active: Boolean) {
        foreground = active
    }

    /**
     * Android 15 allows this kind of service 6 hours a day. With someone in the app it can go on, and coming to
     * the foreground resets the count. With nobody there it has to end now, cleanly, and say why.
     */
    @MainThread
    internal fun onSystemLimit() {
        foreground = false
        if (isAppVisible) {
            scope.launch(Dispatchers.Main) {
                delay(250)
                if (state.value.busy) promote()
            }
        } else {
            limitHit = true
            cancelAll()
        }
    }

    private fun updateProgress(jobs: JobsState) {
        val job = jobs.running ?: return
        val percent = job.fraction?.let { (it * 100).toInt() }
        val key = "${job.id}/$percent/${job.item}/${jobs.waiting}/${job.paused}/${job.stopping}"
        if (key == notifiedKey) return
        val forced = notifiedKey?.substringBefore('/') != job.id.toString() || job.paused || job.stopping
        val time = SystemClock.elapsedRealtime()
        if (!forced && time - notifiedAt < NOTIFY_EVERY_MILLIS) return
        notifiedKey = key
        notifiedAt = time
        try {
            notifications.notify(PROGRESS_ID, progressNotification(jobs))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update the notification", e)
        }
    }

    internal fun progressNotification(jobs: JobsState = state.value): Notification {
        // Before anything can be shown, since a foreground service with no channel to show in is a crash.
        createChannel()
        val job = jobs.running
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_file_job)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
        if (job == null) {
            // Only ever seen for the moment between a service starting and it finding the work is already over.
            return builder.setContentTitle(app.getString(R.string.safety_job_finishing)).build()
        }
        val percent = job.fraction?.let { (it * 100).toInt() }
        builder.setContentTitle(job.title)
        builder.setContentText(progressText(job, jobs.waiting, percent))
        builder.setProgress(100, percent ?: 0, percent == null)
        if (!job.stopping) {
            builder.addAction(0, app.getString(R.string.cancel), FileJobService.cancelIntent(app, all = false))
            if (jobs.waiting > 0) {
                builder.addAction(0, app.getString(R.string.safety_job_cancel_all), FileJobService.cancelIntent(app, all = true))
            }
        }
        return builder.build()
    }

    private fun progressText(job: RunningJob, waiting: Int, percent: Int?): String {
        val parts = ArrayList<String>()
        when {
            job.stopping -> parts += app.getString(R.string.safety_job_stopping)
            job.paused -> parts += app.getString(R.string.paused)
            job.total > 0 -> parts += app.getString(
                R.string.safety_progress_count,
                java.text.NumberFormat.getIntegerInstance().format(job.done),
                java.text.NumberFormat.getIntegerInstance().format(job.total)
            )
            percent != null -> parts += app.getString(R.string.percentage, percent)
        }
        if (!job.stopping) job.item?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (waiting > 0) parts += app.resources.getQuantityString(R.plurals.safety_job_more_waiting, waiting, waiting)
        return parts.joinToString(" · ")
    }

    private fun showResult(job: FinishedJob) {
        val result = job.result
        val detail = if (limitHit) app.getString(R.string.safety_job_time_limit) else result.detail
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_file_job)
            .setContentTitle(result.title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openFolder(job.id, result.folder))
        if (!detail.isNullOrBlank()) {
            builder.setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        }
        try {
            notifications.notify(RESULT_ID_BASE + (job.id % RESULT_IDS).toInt(), builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't post a job's result", e)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app,
        PROGRESS_ID,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openFolder(jobId: Long, folder: String?): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (folder != null) {
            intent.action = ToolsFiles.ACTION_OPEN_FOLDER
            intent.putExtra(ToolsFiles.EXTRA_FOLDER_PATH, folder)
        }
        return PendingIntent.getActivity(
            app,
            RESULT_ID_BASE + (jobId % RESULT_IDS).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Synchronized
    private fun createChannel() {
        if (channelMade) return
        channelMade = true
        val channel = NotificationChannel(CHANNEL_ID, app.getString(R.string.safety_job_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        notifications.createNotificationChannel(channel)
        // Extraction had a channel of its own, before it became one of these.
        notifications.deleteNotificationChannel(OLD_EXTRACT_CHANNEL_ID)
    }

    /**
     * A process's journals are removed as its jobs end, so any here now belong to one that died. Nothing can be
     * running yet, since nothing starts until this is done.
     */
    private fun recover(journals: File) {
        val leftovers = journals.listFiles { _, name -> name.endsWith(JobRunner.JOURNAL_SUFFIX) }?.sortedBy { it.name }.orEmpty()
        if (leftovers.isEmpty()) return
        // A plain notification outlives the process it came from.
        notifications.cancel(PROGRESS_ID)
        if (!canSeeStorage()) {
            // Everything would look gone. The journals keep until it can be looked at properly.
            Log.w(TAG, "Storage access is off, so an interrupted job can't be settled yet")
            return
        }
        val repository = com.kobblo.gallery.file.explorer.FileRepository(app)
        val told = ArrayList<JobNotice>()
        for (journal in leftovers) {
            val record = JobJournal.read(journal) ?: continue
            val settled = JobRecovery.settle(record)
            repository.settleAfterKill(settled)
            told += noticeFor(settled, journal.name.removeSuffix(JobRunner.JOURNAL_SUFFIX).toLongOrNull() ?: System.nanoTime())
        }
        app.cacheDir.listFiles { _, name -> name.startsWith("rar_") || name.startsWith("extract_") }?.forEach { SafeFiles.deleteTree(it) }
        _notices.update { it + told }
        saveNotices()
        // Last, so that being killed again in the middle of this only means looking again, and nobody goes untold.
        leftovers.forEach { it.delete() }
    }

    private fun canSeeStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun noticeFor(job: SettledJob, id: Long): JobNotice {
        val res = app.resources
        val name = job.label
        if (!job.ran) {
            val title = when (job.kind) {
                JobKind.COPY -> app.getString(R.string.safety_notice_copy_waiting, name)
                JobKind.MOVE -> app.getString(R.string.safety_notice_move_waiting, name)
                JobKind.ORGANIZE -> app.getString(R.string.safety_notice_organize_waiting)
                JobKind.TRASH, JobKind.DELETE -> app.getString(R.string.safety_notice_delete_waiting)
                JobKind.RESTORE -> app.getString(R.string.safety_notice_restore_waiting)
                JobKind.EMPTY_BIN -> app.getString(R.string.safety_notice_delete_waiting)
                JobKind.COMPRESS -> app.getString(R.string.safety_notice_compress_waiting, name)
                JobKind.EXTRACT -> app.getString(R.string.safety_notice_extract_waiting, name)
                JobKind.VAULT -> app.getString(R.string.safety_notice_vault_waiting)
            }
            return JobNotice(id, title, app.getString(R.string.safety_notice_never_started), null)
        }
        if (job.archiveMade != null) {
            return JobNotice(
                id,
                app.getString(R.string.safety_job_compressed, File(job.archiveMade).name),
                app.getString(R.string.safety_notice_archive_made),
                job.folder
            )
        }
        val parts = ArrayList<String>()
        parts += app.getString(R.string.safety_notice_closed)
        val title = when (job.kind) {
            JobKind.COPY -> {
                // With one item, that it was removed says it all, and "0 of 1" only sounds like a machine.
                if (job.total > 1) parts += res.getQuantityString(R.plurals.safety_notice_copy_count, job.total, job.finished, job.total)
                if (job.partialsRemoved > 0) parts += app.getString(R.string.safety_notice_copy_removed)
                parts += app.getString(R.string.safety_notice_originals_safe)
                app.getString(R.string.safety_notice_copy, name)
            }
            JobKind.MOVE, JobKind.ORGANIZE -> {
                if (job.total > 1) {
                    parts += res.getQuantityString(R.plurals.safety_notice_move_count, job.total, job.finished, job.total)
                } else if (job.finished == 0) {
                    parts += app.getString(R.string.safety_notice_move_none)
                }
                if (job.partialsRemoved > 0) parts += app.getString(R.string.safety_notice_copy_removed)
                if (job.kind == JobKind.ORGANIZE) app.getString(R.string.safety_notice_organize) else app.getString(R.string.safety_notice_move, name)
            }
            JobKind.TRASH -> {
                parts += res.getQuantityString(R.plurals.safety_notice_trash_count, job.total, job.finished, job.total)
                app.getString(R.string.safety_notice_delete)
            }
            JobKind.DELETE -> {
                parts += res.getQuantityString(R.plurals.safety_notice_delete_count, job.total, job.finished, job.total)
                app.getString(R.string.safety_notice_delete)
            }
            JobKind.RESTORE -> {
                parts += res.getQuantityString(R.plurals.safety_notice_restore_count, job.total, job.finished, job.total)
                app.getString(R.string.safety_notice_restore)
            }
            JobKind.EMPTY_BIN -> {
                parts += res.getQuantityString(R.plurals.safety_notice_empty_count, job.total, job.finished, job.total)
                app.getString(R.string.safety_notice_delete)
            }
            JobKind.COMPRESS -> {
                parts += app.getString(R.string.safety_notice_compress_detail)
                app.getString(R.string.safety_notice_compress, name)
            }
            JobKind.EXTRACT -> {
                if (job.partialsKept.isEmpty()) parts += app.getString(R.string.safety_notice_extract_detail)
                app.getString(R.string.safety_notice_extract, name)
            }
            JobKind.VAULT -> {
                parts += app.getString(R.string.safety_notice_vault_detail)
                app.getString(R.string.safety_notice_vault)
            }
        }
        job.originalsLeft.firstOrNull()?.let { left ->
            parts += app.getString(R.string.safety_notice_original_left, File(left).name, left)
        }
        job.partialsKept.firstOrNull()?.let { kept ->
            parts += app.getString(R.string.safety_notice_partial_kept, kept)
        }
        // An extraction's folder may be just what was cleaned away.
        return JobNotice(id, title, parts.joinToString(" "), job.folder?.takeIf { File(it).isDirectory })
    }

    private fun loadNotices() {
        val stored = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_NOTICES, null) ?: return
        val notices = try {
            val array = JSONArray(stored)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                JobNotice(
                    id = item.getLong("id"),
                    title = item.getString("title"),
                    body = item.getString("body"),
                    folder = item.optString("folder").ifEmpty { null }
                )
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
        _notices.update { it + notices }
    }

    private fun saveNotices() {
        val array = JSONArray()
        for (notice in _notices.value) {
            array.put(
                JSONObject()
                    .put("id", notice.id)
                    .put("title", notice.title)
                    .put("body", notice.body)
                    .put("folder", notice.folder.orEmpty())
            )
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_NOTICES, array.toString()).apply()
    }
}
