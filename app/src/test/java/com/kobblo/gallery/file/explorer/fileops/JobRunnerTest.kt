package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class JobRunnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var journals: File
    private lateinit var runner: JobRunner

    @Before
    fun setUp() {
        journals = temp.newFolder("journals")
        runner = JobRunner(scope, journals)
        runner.openForWork()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun spec(
        name: String = "job",
        reads: List<String> = emptyList(),
        writes: List<String> = emptyList(),
        onDone: (JobResult) -> Unit = {},
        work: suspend (JobControl) -> JobResult = { done() }
    ) = JobSpec(
        kind = JobKind.COPY,
        surface = JobSurface.BAR,
        title = name,
        plan = JobPlan(JobKind.COPY, name, null, reads),
        reads = reads,
        writes = writes,
        onStopped = { JobResult(JobOutcome.STOPPED, "stopped $name") },
        onFailed = { JobResult(JobOutcome.PROBLEM, "failed $name", it.message) },
        onDone = onDone,
        work = work
    )

    private fun done(title: String = "done") = JobResult(JobOutcome.DONE, title)

    private suspend fun idle() = withTimeout(5_000) { runner.state.first { !it.busy } }

    @Test
    fun `jobs run one at a time and in the order they were asked for`() = runBlocking<Unit> {
        val order = Collections.synchronizedList(ArrayList<String>())
        var running = 0
        var most = 0
        for (name in listOf("a", "b", "c")) {
            runner.enqueue(spec(name, work = {
                synchronized(order) { running++; most = maxOf(most, running) }
                order += "$name start"
                delay(30)
                order += "$name end"
                synchronized(order) { running-- }
                done()
            }))
        }
        idle()
        assertEquals(listOf("a start", "a end", "b start", "b end", "c start", "c end"), order.toList())
        assertEquals(1, most)
    }

    @Test
    fun `a job that's waiting is counted and starts when the one before it finishes`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val first = runner.enqueue(spec("first", work = { release.await(); done() }))
        runner.enqueue(spec("second"))
        val state = withTimeout(5_000) { runner.state.first { it.running?.id == first && it.waiting == 1 } }
        assertEquals("first", state.running?.title)
        release.complete(Unit)
        idle()
    }

    @Test
    fun `nothing starts until what the last process left has been looked at`() = runBlocking<Unit> {
        val closed = JobRunner(scope, temp.newFolder("closed"))
        val started = CompletableDeferred<Unit>()
        closed.enqueue(spec("waiting", work = { started.complete(Unit); done() }))
        delay(100)
        assertFalse(started.isCompleted)
        closed.openForWork()
        withTimeout(5_000) { started.await() }
        Unit
    }

    @Test
    fun `a stop thrown at a checkpoint is an ordinary end and the next job still runs`() = runBlocking<Unit> {
        val results = Collections.synchronizedList(ArrayList<String>())
        val entered = CompletableDeferred<Unit>()
        val first = runner.enqueue(spec("first", onDone = { results += it.title }, work = { control ->
            entered.complete(Unit)
            while (true) {
                control.checkpoint()
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE")
            done()
        }))
        runner.enqueue(spec("second", onDone = { results += it.title }))
        entered.await()
        runner.cancel(first)
        idle()
        assertEquals(listOf("stopped first", "done"), results.toList())
    }

    @Test
    fun `a stop for a job still waiting means it never touches anything`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var ran = false
        runner.enqueue(spec("first", work = { release.await(); done() }))
        val second = runner.enqueue(spec("second", work = { ran = true; done() }))
        runner.cancel(second)
        release.complete(Unit)
        idle()
        assertFalse(ran)
    }

    @Test
    fun `cancel all stops what's running and everything behind it`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        var ranLater = false
        runner.enqueue(spec("first", work = { control ->
            entered.complete(Unit)
            while (true) {
                control.checkpoint()
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE")
            done()
        }))
        runner.enqueue(spec("second", work = { ranLater = true; done() }))
        entered.await()
        runner.cancelAll()
        idle()
        assertFalse(ranLater)
    }

    @Test
    fun `a pause holds a job at its next checkpoint until it's undone`() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val passed = CompletableDeferred<Unit>()
        runner.enqueue(spec("paused", work = { control ->
            reached.complete(Unit)
            delay(50)
            control.checkpoint()
            passed.complete(Unit)
            done()
        }))
        reached.await()
        runner.togglePause()
        delay(150)
        assertFalse(passed.isCompleted)
        assertTrue(runner.state.value.running?.paused == true)
        runner.togglePause()
        withTimeout(5_000) { passed.await() }
        idle()
    }

    @Test
    fun `a stop gets through a pause`() = runBlocking<Unit> {
        val results = Collections.synchronizedList(ArrayList<String>())
        val reached = CompletableDeferred<Unit>()
        val id = runner.enqueue(spec("paused", onDone = { results += it.title }, work = { control ->
            reached.complete(Unit)
            delay(50)
            control.checkpoint()
            done()
        }))
        reached.await()
        runner.togglePause()
        delay(100)
        runner.cancel(id)
        idle()
        assertEquals(listOf("stopped paused"), results.toList())
    }

    @Test
    fun `a job that throws is reported and doesn't stop the queue`() = runBlocking<Unit> {
        val results = Collections.synchronizedList(ArrayList<String>())
        runner.enqueue(spec("bad", onDone = { results += it.title }, work = { throw IllegalStateException("broken") }))
        runner.enqueue(spec("good", onDone = { results += it.title }))
        idle()
        assertEquals(listOf("failed bad", "done"), results.toList())
    }

    @Test
    fun `a job's note is there while it runs and gone once it has finished`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        runner.enqueue(spec("noted", reads = listOf("/x/a"), work = { entered.complete(Unit); release.await(); done() }))
        entered.await()
        val note = journals.listFiles().orEmpty().single()
        val record = JobJournal.read(note)
        assertNotNull(record)
        assertEquals(listOf("/x/a"), record!!.sources)
        assertTrue(record.ran)
        release.complete(Unit)
        idle()
        delay(50)
        assertEquals(0, journals.listFiles().orEmpty().size)
    }

    @Test
    fun `progress shows as a fraction whether it comes as bytes or as a count`() = runBlocking<Unit> {
        val step = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        runner.enqueue(spec("counted", work = { control ->
            control.setCount(5, 20)
            delay(120)
            control.setItem("a.jpg")
            step.complete(Unit)
            finish.await()
            done()
        }))
        step.await()
        val job = withTimeout(5_000) { runner.state.first { it.running?.item == "a.jpg" } }.running!!
        assertEquals(0.25f, job.fraction!!, 0.001f)
        assertEquals(5, job.done)
        assertEquals(20, job.total)
        finish.complete(Unit)
        idle()
    }

    @Test
    fun `a job with no way to say how far it's got has no fraction`() = runBlocking<Unit> {
        val finish = CompletableDeferred<Unit>()
        runner.enqueue(spec("unknown", work = { finish.await(); done() }))
        val job = withTimeout(5_000) { runner.state.first { it.running != null } }.running!!
        assertNull(job.fraction)
        finish.complete(Unit)
        idle()
    }

    @Test
    fun `busy covers what a job reads and writes, running or waiting`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        runner.enqueue(spec("a", reads = listOf("/x/src"), writes = listOf("/y/dst"), work = { release.await(); done() }))
        runner.enqueue(spec("b", reads = listOf("/z/other")))
        assertTrue(runner.isBusyWith("/x/src"))
        assertTrue(runner.isBusyWith("/x"))
        assertTrue(runner.isBusyWith("/x/src/inner.jpg"))
        assertTrue(runner.isBusyWith("/y/dst"))
        assertTrue(runner.isBusyWith("/z/other"))
        assertFalse(runner.isBusyWith("/x/sibling"))
        release.complete(Unit)
        idle()
        assertFalse(runner.isBusyWith("/x/src"))
    }
}

class JobRulesTest {
    @Test
    fun `what's being read can't be touched, nor anything in it or above it`() {
        val reads = listOf("/a/b")
        assertTrue(JobRules.conflicts("/a/b", reads, emptyList()))
        assertTrue(JobRules.conflicts("/a", reads, emptyList()))
        assertTrue(JobRules.conflicts("/a/b/c.txt", reads, emptyList()))
        assertFalse(JobRules.conflicts("/a/bb", reads, emptyList()))
        assertFalse(JobRules.conflicts("/a/c", reads, emptyList()))
    }

    @Test
    fun `a folder being written into only minds itself and what's above it`() {
        val writes = listOf("/a/b")
        assertTrue(JobRules.conflicts("/a/b", emptyList(), writes))
        assertTrue(JobRules.conflicts("/a", emptyList(), writes))
        // Anything else in the folder is nobody's business but its owner's.
        assertFalse(JobRules.conflicts("/a/b/other.txt", emptyList(), writes))
        assertFalse(JobRules.conflicts("/a/c", emptyList(), writes))
    }

    @Test
    fun `a slash on the end changes nothing`() {
        assertTrue(JobRules.conflicts("/a/b/", listOf("/a/b"), emptyList()))
    }
}
