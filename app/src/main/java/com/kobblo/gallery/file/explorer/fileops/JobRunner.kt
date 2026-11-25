package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

enum class JobKind { COPY, MOVE, ORGANIZE, TRASH, DELETE, RESTORE, EMPTY_BIN, COMPRESS, EXTRACT, VAULT }

/** Where the foreground shows a job. Each keeps the pill, bar or dialog it always had. */
enum class JobSurface { PASTE_PILL, EXTRACT_PILL, COMPRESS_DIALOG, BAR, OWN }

/** What's under way, for anything that draws it. */
data class RunningJob(
    val id: Long,
    val kind: JobKind,
    val surface: JobSurface,
    val title: String,
    /** Where it's going, or the archive it's about. */
    val target: String?,
    val item: String?,
    val done: Int,
    val total: Int,
    /** Null until something says how far along it is. Not every format can. */
    val fraction: Float?,
    val paused: Boolean,
    val stopping: Boolean
)

data class JobsState(val running: RunningJob? = null, val waiting: Int = 0) {
    val busy: Boolean get() = running != null || waiting > 0
}

enum class JobOutcome { DONE, PROBLEM, STOPPED }

/**
 * How a job ended. [title] and [detail] are for someone who wasn't watching, [message] for someone who was.
 * The person sees one or the other, not both.
 */
data class JobResult(
    val outcome: JobOutcome,
    val title: String,
    val detail: String? = null,
    val message: String? = null,
    /** What tapping the result opens. */
    val folder: String? = null,
    val payload: Any? = null
)

data class FinishedJob(val id: Long, val kind: JobKind, val result: JobResult)

/** What running work can ask of the runner. */
internal interface JobControl {
    val stopRequested: Boolean
    val journal: JobJournal

    /** Waits out a pause, and throws [CancellationException] once a stop has been asked for. */
    suspend fun checkpoint()
    fun setFraction(value: Float)
    fun setCount(done: Int, total: Int = -1)
    fun setItem(name: String?)
}

internal class JobSpec(
    val kind: JobKind,
    val surface: JobSurface,
    val title: String,
    val target: String? = null,
    val total: Int = 0,
    /** What's written down before it starts, so a kill can be explained later. */
    val plan: JobPlan,
    /** Being read, or taken away. Nothing else should rename these meanwhile. */
    val reads: List<String> = plan.sources,
    /** Being written into. */
    val writes: List<String> = listOfNotNull(plan.folder),
    /** What to say if it never got to finish, because a stop came first or the work threw one. */
    val onStopped: () -> JobResult,
    val onFailed: (Throwable) -> JobResult,
    /** For a screen with its own state to settle. It may be gone, so nothing may depend on this having run. */
    val onDone: (JobResult) -> Unit = {},
    val work: suspend (JobControl) -> JobResult
)

/**
 * Runs file work one job at a time, in the order it was asked for.
 *
 * One at a time because side by side on the one disk isn't faster, and because order is what stops two jobs
 * trampling each other: move a folder while it's being copied and the move simply waits, then finds the
 * folder where it always was. Delete it, and the delete waits too.
 *
 * This outlives every screen, and holds no Android types, so what it promises can be tested on the JVM.
 */
internal class JobRunner(
    private val scope: CoroutineScope,
    private val journalDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val workContext: CoroutineContext = Dispatchers.IO
) {
    private val lock = Any()
    private val queue = ArrayDeque<Entry>()
    private var current: Entry? = null
    private var lastId = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val open = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow(JobsState())
    val state: StateFlow<JobsState> = _state.asStateFlow()

    // Unsuspending subscribers get every result, and a slow one can't hold up the work.
    private val _finished = MutableSharedFlow<FinishedJob>(extraBufferCapacity = 32)
    val finished: SharedFlow<FinishedJob> = _finished.asSharedFlow()

    init {
        scope.launch {
            open.await()
            while (true) {
                val next = synchronized(lock) { queue.removeFirstOrNull()?.also { current = it } }
                if (next == null) {
                    wake.receive()
                } else {
                    run(next)
                }
            }
        }
    }

    /** Nothing starts until what an earlier process left behind has been looked at. */
    fun openForWork() {
        open.complete(Unit)
    }

    fun enqueue(spec: JobSpec): Long {
        val entry = synchronized(lock) {
            lastId = maxOf(lastId + 1, now())
            Entry(lastId, spec, JobJournal(File(journalDir, "$lastId$JOURNAL_SUFFIX"))).also { queue.addLast(it) }
        }
        // Off the caller's thread: a selection of thousands is a long list to write. If the process dies in the
        // moment before this lands, nothing has started either.
        entry.written = scope.launch(workContext) { entry.journal.begin(entry.id, spec.plan, now()) }
        publish()
        wake.trySend(Unit)
        return entry.id
    }

    fun cancel(id: Long) {
        val dropped = synchronized(lock) {
            val running = current
            if (running?.id == id) {
                running.stopRequested = true
                null
            } else {
                queue.firstOrNull { it.id == id }?.also { queue.remove(it) }
            }
        }
        dropped?.let(::endWithoutRunning)
        publish()
    }

    fun cancelRunning() {
        synchronized(lock) { current?.stopRequested = true }
        publish()
    }

    fun cancelAll() {
        val dropped = synchronized(lock) {
            current?.stopRequested = true
            queue.toList().also { queue.clear() }
        }
        dropped.forEach(::endWithoutRunning)
        publish()
    }

    fun togglePause() {
        synchronized(lock) { current?.let { it.paused = !it.paused } }
        publish()
    }

    /** Whether renaming or hiding [path] now would pull something out from under a job, running or waiting. */
    fun isBusyWith(path: String): Boolean = synchronized(lock) {
        (listOfNotNull(current) + queue).any { JobRules.conflicts(path, it.spec.reads, it.spec.writes) }
    }

    private fun endWithoutRunning(entry: Entry) {
        scope.launch(workContext) {
            entry.written?.join()
            entry.journal.delete()
            report(entry, entry.spec.onStopped())
        }
    }

    private suspend fun run(entry: Entry) {
        val spec = entry.spec
        entry.written?.join()
        publish()
        val result = try {
            // A stop can arrive before its turn really begins, and then nothing at all should be touched.
            entry.checkpoint()
            withContext(workContext) {
                entry.journal.ran()
                spec.work(entry)
            }
        } catch (e: CancellationException) {
            // A stop thrown from a checkpoint is an ordinary end. Any other means this whole scope is going.
            if (!entry.stopRequested) throw e
            spec.onStopped()
        } catch (e: Throwable) {
            spec.onFailed(e)
        }
        // Gone before anything can say it finished: a kill right here costs a redundant note at worst.
        withContext(workContext) { entry.journal.delete() }
        synchronized(lock) { current = null }
        publish()
        report(entry, result)
    }

    private fun report(entry: Entry, result: JobResult) {
        _finished.tryEmit(FinishedJob(entry.id, entry.spec.kind, result))
        try {
            entry.spec.onDone(result)
        } catch (e: Exception) {
            // A screen's bookkeeping going wrong can't be allowed to stop the jobs behind this one.
            android.util.Log.e("JobRunner", "A job's finish handler failed", e)
        }
    }

    private fun publish() {
        _state.value = synchronized(lock) { JobsState(current?.snapshot(), queue.size) }
    }

    private inner class Entry(val id: Long, val spec: JobSpec, override val journal: JobJournal) : JobControl {
        var written: Job? = null

        @Volatile
        override var stopRequested = false

        @Volatile
        var paused = false

        @Volatile
        private var done = 0

        @Volatile
        private var total = spec.total

        @Volatile
        private var fraction: Float? = null

        @Volatile
        private var item: String? = null

        private var publishedAt = 0L

        override suspend fun checkpoint() {
            while (paused && !stopRequested) delay(PAUSE_POLL_MILLIS)
            if (stopRequested) throw CancellationException("Stopped")
        }

        override fun setFraction(value: Float) {
            fraction = value.coerceIn(0f, 1f)
            publishSoon()
        }

        override fun setCount(done: Int, total: Int) {
            if (total >= 0) this.total = total
            this.done = done
            publishSoon()
        }

        override fun setItem(name: String?) {
            item = name
            publishSoon()
        }

        /** Work reports far more often than anything can draw, so most of this is thrown away. */
        private fun publishSoon() {
            val time = System.nanoTime()
            if (time - publishedAt < PUBLISH_EVERY_NANOS) return
            publishedAt = time
            publish()
        }

        fun snapshot() = RunningJob(
            id = id,
            kind = spec.kind,
            surface = spec.surface,
            title = spec.title,
            target = spec.target,
            item = item,
            done = done,
            total = total,
            fraction = fraction ?: total.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) },
            paused = paused,
            stopping = stopRequested
        )
    }

    companion object {
        const val JOURNAL_SUFFIX = ".job"
        private const val PAUSE_POLL_MILLIS = 100L
        private const val PUBLISH_EVERY_NANOS = 100_000_000L
    }
}

/** Which paths two pieces of work can't share. */
internal object JobRules {
    /**
     * A job reading or taking away a path is upset by anything happening to it, to what's in it, or to a folder
     * above it. A job writing into a folder only minds that folder or one above it going: what else is in
     * there isn't its concern.
     */
    fun conflicts(path: String, reads: List<String>, writes: List<String>): Boolean {
        val target = path.trimEnd('/')
        val inside = "$target/"
        return reads.any { read -> read == target || read.startsWith(inside) || target.startsWith("$read/") } ||
            writes.any { write -> write == target || write.startsWith(inside) }
    }
}
