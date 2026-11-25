package com.kobblo.gallery.file.explorer.files

import android.content.pm.PackageManager
import android.os.SystemClock
import com.kobblo.gallery.file.explorer.fileops.PrivilegedShell
import rikka.shizuku.Shizuku

/** Android keeps these folders for the apps they belong to, so it takes a more privileged shell to open one. */
internal fun isRestrictedPath(path: String): Boolean = "Android/data" in path || "Android/obb" in path

/** The shell that reaches where the app itself can't, as the rest of this package needs it. */
internal interface ElevatedShell : PrivilegedShell {
    /** Whether there is such a shell right now. The person can start and stop it at any moment. */
    val isReady: Boolean

    /** What a command printed, line by line, or null if it couldn't be started. */
    fun linesOf(command: String): List<String>?

    /** Whether a command succeeded, and everything it said. */
    fun runForOutput(command: String): Pair<Boolean, String>
}

/** Whether [path] is one that has to go through this shell, and can. */
internal fun ElevatedShell.mustHandle(path: String): Boolean = isRestrictedPath(path) && isReady

/** The shell Shizuku lends an app the person has allowed to use it. */
internal class ShizukuShell : ElevatedShell {
    private class Answer(val ready: Boolean, val at: Long)

    @Volatile
    private var lastAnswer: Answer? = null

    /**
     * Asking Shizuku is a trip to another process, and some screens want to know on every change of their
     * state, so an answer is good for a moment.
     */
    override val isReady: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            lastAnswer?.let { if (now - it.at < AnswerMillis) return it.ready }
            return ask().also { lastAnswer = Answer(it, now) }
        }

    fun forgetAnswer() {
        lastAnswer = null
    }

    private fun ask(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** Nobody reads this output, and a command that fills the pipe would never exit, so the shell drops it. */
    override fun run(command: String): Boolean = try {
        start("( $command ) >/dev/null 2>&1")?.waitFor() == 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    override fun linesOf(command: String): List<String>? {
        val process = start(command) ?: return null
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        return lines
    }

    override fun runForOutput(command: String): Pair<Boolean, String> = try {
        val process = start(command)
        if (process == null) {
            false to "Process is null"
        } else {
            val said = listOf(process.inputStream, process.errorStream).flatMap { it.bufferedReader().readLines() }
            (process.waitFor() == 0) to said.joinToString(separator = "") { it + LineEnd }
        }
    } catch (e: Exception) {
        false to (e.message ?: "Unknown error")
    }

    /** Shizuku has the call that starts a process but doesn't offer it to apps, so it's looked up by name. */
    private fun start(command: String): Process? {
        val newProcess = Shizuku::class.java.declaredMethods.find { it.name == "newProcess" } ?: return null
        newProcess.isAccessible = true
        return newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    }

    private companion object {
        const val AnswerMillis = 2000L

        /** A backslash and an n, so that however much a command says is one line of text. */
        const val LineEnd = "\\n"
    }
}
