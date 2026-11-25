package com.kobblo.gallery.file.explorer.transfer.core

import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs each connection on a thread, as NanoHTTPD does by default, but only so many at once. Without a
 * limit, anyone on the network who opens connections and sits on them costs this phone a thread each
 * until it can't make another, which takes the whole app down. Past the limit, a new one is just closed.
 */
class BoundedAsyncRunner(private val maxConnections: Int) : NanoHTTPD.AsyncRunner {
    private val running = Collections.synchronizedList(ArrayList<NanoHTTPD.ClientHandler>())
    private val count = AtomicLong()

    override fun closeAll() {
        // Copied, because closing one removes it from the list.
        ArrayList(running).forEach { it.close() }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun exec(code: NanoHTTPD.ClientHandler) {
        if (running.size >= maxConnections) {
            code.close()
            return
        }
        running.add(code)
        Thread(code, "NanoHttpd Request Processor (#${count.incrementAndGet()})").apply {
            isDaemon = true
            start()
        }
    }
}
