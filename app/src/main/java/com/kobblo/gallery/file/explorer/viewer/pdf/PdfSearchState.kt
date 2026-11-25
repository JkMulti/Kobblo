package com.kobblo.gallery.file.explorer.viewer.pdf

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the search bar shows and the page canvas highlights. Results arrive on Binder threads and land here on the main one. */
@Stable
internal class PdfSearchState(
    private val doc: PdfDoc,
    private val reader: PdfReaderState,
    private val scope: CoroutineScope
) {
    val results = PdfSearchResults()

    var isOpen by mutableStateOf(false)
    var query by mutableStateOf("")

    /** The words that the results are for, which trails [query] by the wait for typing to stop. */
    var searchedFor by mutableStateOf("")
        private set
    var isRunning by mutableStateOf(false)
        private set
    var pagesScanned by mutableIntStateOf(0)
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var currentIndex by mutableIntStateOf(-1)
        private set
    var wasCut by mutableStateOf(false)
        private set

    /** Bumped as results change, for the canvas. */
    var revision by mutableIntStateOf(0)
        private set

    /** Whether a search that found nothing was looking at text at all, as a scanned page has none. */
    var noTextOnPage by mutableStateOf(false)
        private set

    private var latestId = 0

    fun start(text: String) {
        val trimmed = PdfTextMatch.normalizeQuery(text)
        if (trimmed == searchedFor && (isRunning || matchCount > 0)) return
        // Not cancelled on the other side, which might reach it after the new search. That replaces it anyway.
        clearResults(cancel = false)
        searchedFor = trimmed
        if (trimmed.isEmpty()) return
        isRunning = true
        val id = ++latestId
        val startPage = reader.currentPage
        scope.launch {
            doc.search(id, trimmed, startPage, callbackFor(id))
            // If the process dies partway, nothing will ever say this search is over.
            while (isRunning && latestId == id) {
                delay(WatchMs)
                if (!doc.isProcessAlive && latestId == id) {
                    isRunning = false
                    wasCut = matchCount > 0
                }
            }
        }
    }

    private fun callbackFor(id: Int) = object : IPdfSearchCallback.Stub() {
        override fun onMatches(searchId: Int, page: Int, rects: FloatArray, rectsPerMatch: IntArray) {
            scope.launch(Dispatchers.Main.immediate) {
                if (searchId != latestId || id != latestId) return@launch
                var at = 0
                val hits = ArrayList<PdfHit>(rectsPerMatch.size)
                for (count in rectsPerMatch) {
                    if (count > 0) hits.add(PdfHit(page, rects.copyOfRange(at, at + count * 4)))
                    at += count * 4
                }
                val isFirst = results.count == 0
                results.add(page, hits)
                matchCount = results.count
                if (isFirst && hits.isNotEmpty()) {
                    results.selectFrom(reader.currentPage)?.let(reader::show)
                }
                currentIndex = results.currentIndex
                if (results.count >= MaxMatches) {
                    wasCut = true
                    doc.cancelSearch()
                }
                revision++
            }
        }

        override fun onProgress(searchId: Int, pagesScanned: Int) {
            scope.launch(Dispatchers.Main.immediate) {
                if (searchId == latestId) this@PdfSearchState.pagesScanned = pagesScanned
            }
        }

        override fun onFinished(searchId: Int, complete: Boolean) {
            scope.launch(Dispatchers.Main.immediate) {
                if (searchId != latestId) return@launch
                isRunning = false
                if (complete) pagesScanned = doc.pageCount
                if (results.count == 0) {
                    noTextOnPage = doc.pageText(reader.currentPage).isBlank()
                }
            }
        }
    }

    fun next() {
        results.next(reader.currentPage)?.let(reader::show)
        afterMove()
    }

    fun previous() {
        results.previous(reader.currentPage)?.let(reader::show)
        afterMove()
    }

    private fun afterMove() {
        currentIndex = results.currentIndex
        revision++
    }

    fun close() {
        isOpen = false
        query = ""
        clearResults()
        searchedFor = ""
    }

    private fun clearResults(cancel: Boolean = true) {
        latestId++
        if (cancel) doc.cancelSearch()
        results.clear()
        isRunning = false
        pagesScanned = 0
        matchCount = 0
        currentIndex = -1
        wasCut = false
        noTextOnPage = false
        revision++
    }

    companion object {
        /** A word on every line of a long document stops being a search. This holds a few hundred kilobytes. */
        const val MaxMatches = 10_000
        private const val WatchMs = 1000L
    }
}
