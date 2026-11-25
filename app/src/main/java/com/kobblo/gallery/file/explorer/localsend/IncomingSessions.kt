package com.kobblo.gallery.file.explorer.localsend

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** One file a sender has been told it may upload, and everything agreed about it when it was accepted. */
internal class AcceptedFile(
    /** As the sender described it. Its name hasn't been made safe: [relativeName] is the one to use. */
    val offered: FileDto,
    /** What the sender must show to upload this file, and only this one. */
    val token: String,
    /** Where it goes under the receive folder, already made safe. */
    val relativeName: String,
    /**
     * The hidden file beside its destination that holds what has arrived so far, so a dropped connection can
     * carry on from there. Only a sender that proved which device it is gets one.
     */
    val keptPart: String? = null,
    /** How much of it is in [keptPart] from an earlier try. Null when it starts from nothing. */
    val resumeFrom: Long? = null
) {
    /** Compared in constant time, so how long a wrong guess takes says nothing about how close it was. */
    fun isUnlockedBy(candidate: String): Boolean = MessageDigest.isEqual(token.toByteArray(), candidate.toByteArray())
}

/**
 * Everything a sender is uploading after one prepare-upload was accepted. Its files may arrive several at a
 * time, each on a connection and a thread of its own, so every part of this can be touched from any of them.
 */
internal class IncomingSession(
    val id: String,
    val sender: DeviceDto,
    /** The address the offer came from. The protocol ties a session to it: nothing else may add to or end it. */
    val senderIp: String,
    /** The receive folder as it was when this was accepted, in case the setting changes halfway. */
    val root: File,
    /** Whose leave to carry on without being asked again this is. */
    val grantHolder: String,
    /** By file id. */
    val files: Map<String, AcceptedFile>
) {
    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    /** Nothing more of it is wanted. Uploads under way stop, and remove what they'd written. */
    @Volatile
    var isStopped: Boolean = false
        private set

    /**
     * It was stopped because the same device started again. What it left half-written is exactly what the
     * new session will want to carry on from, so its uploads end without removing anything.
     */
    @Volatile
    var isReplaced: Boolean = false
        private set

    private val arriving = ConcurrentHashMap<String, InputStream>()
    private val claimed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val finished: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val saved: MutableList<File> = Collections.synchronizedList(ArrayList())

    val isComplete: Boolean get() = finished.size >= files.size

    /** Whether any upload is still being handled. */
    val hasUploadsRunning: Boolean get() = claimed.isNotEmpty()

    fun savedFiles(): List<File> = synchronized(saved) { saved.toList() }

    fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun stop() {
        isStopped = true
    }

    /**
     * Takes [fileId] for one upload. False if it has already been saved, or is arriving right now: a file
     * accepted once is saved once, however many times it's sent. Whoever gets a yes must [release] it.
     */
    fun claim(fileId: String): Boolean = fileId !in finished && claimed.add(fileId)

    fun release(fileId: String) {
        arriving.remove(fileId)
        claimed.remove(fileId)
    }

    /** Notes the connection [fileId] is arriving on, so it can be cut if this session is replaced. */
    fun arrivingOn(fileId: String, connection: InputStream) {
        arriving[fileId] = connection
    }

    fun markSaved(fileId: String, file: File) {
        saved.add(file)
        finished.add(fileId)
    }

    /**
     * Ends it on behalf of a newer session from the same device. A dropped connection can take this end a
     * long while to notice, so every upload's connection is closed now, and then they're given [waitMs] to
     * let go of their files.
     */
    fun stopForReplacement(waitMs: Long) {
        isReplaced = true
        isStopped = true
        for (connection in arriving.values.toList()) {
            // Each on a thread of its own, because closing a TLS connection tries to say goodbye over it first.
            thread(isDaemon = true) { runCatching { connection.close() } }
        }
        var waited = 0L
        while (hasUploadsRunning && waited < waitMs) {
            Thread.sleep(LET_GO_STEP_MS)
            waited += LET_GO_STEP_MS
        }
    }

    private companion object {
        const val LET_GO_STEP_MS = 50L
    }
}

/**
 * The sessions being received right now. Whoever takes one out of here is the one who says how it ended, so
 * however many things end a session at the same moment, such as its last file landing as the sender cancels and
 * the clock runs out, the rest of the app hears about it once.
 */
internal class IncomingSessions {
    private val open = ConcurrentHashMap<String, IncomingSession>()

    val isEmpty: Boolean get() = open.isEmpty()

    val count: Int get() = open.size

    fun add(session: IncomingSession) {
        open[session.id] = session
    }

    operator fun get(id: String): IncomingSession? = open[id]

    /** True for the one caller that ended [session]. Everyone else finds it already gone. */
    fun end(session: IncomingSession): Boolean = open.remove(session.id) != null

    /** What this device was already sending from this address, if anything. */
    fun from(ip: String, fingerprint: String): IncomingSession? =
        open.values.firstOrNull { it.senderIp == ip && it.sender.fingerprint.equals(fingerprint, ignoreCase = true) }

    /** Sessions nothing has arrived on for [idleMs]. Their senders are gone, whatever the sockets say. */
    fun stalled(nowMs: Long, idleMs: Long): List<IncomingSession> = open.values.filter { nowMs - it.lastActivityMs >= idleMs }

    /** Stops everything, without a word to anyone, for when the server itself is going away. */
    fun abandonAll() {
        open.values.forEach { it.stop() }
        open.clear()
    }
}

/**
 * Tells the media library about files as they land, in batches. Often enough that photos turn up in the
 * gallery while a transfer is still going, and far less often than once a file, which for a folder of
 * thousands would be thousands of trips through the system.
 */
internal class ScanBatch(
    private val batchSize: Int = 64,
    private val everyMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scan: (Array<String>) -> Unit
) {
    private val waiting = ArrayList<String>()
    private var lastSentMs = 0L

    /** Queues [path]. It goes at once if the batch is full, or if it's been a while since the last one went. */
    fun add(path: String) {
        val due = synchronized(waiting) {
            waiting.add(path)
            if (waiting.size < batchSize && clock() - lastSentMs < everyMs) null else drain()
        }
        due?.let(scan)
    }

    /** Sends whatever is waiting, for when a transfer ends and nothing more is coming to fill the batch. */
    fun flush() {
        val due = synchronized(waiting) { if (waiting.isEmpty()) null else drain() }
        due?.let(scan)
    }

    private fun drain(): Array<String> {
        lastSentMs = clock()
        return waiting.toTypedArray().also { waiting.clear() }
    }
}
