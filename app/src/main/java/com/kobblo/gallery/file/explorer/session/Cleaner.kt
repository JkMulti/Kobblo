package com.kobblo.gallery.file.explorer.session

import android.os.SystemClock
import android.util.Log
import com.kobblo.gallery.file.explorer.CleanerData
import com.kobblo.gallery.file.explorer.FileRepository
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Cleaner: what its walk of the phone found, what's chosen of it, and the totals Browse shows. */
internal class Cleaner(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository
) {
    private var walk: Deferred<CleanerData>? = null
    private var walkedAt = 0L
    private var totals: Job? = null

    fun show(visible: Boolean) {
        live.update { it.copy(showCleanerScreen = visible) }
        if (!visible) {
            stopUnlessAwaited()
            return
        }
        // What was found last time shows straight away while the walk checks it again. Coming straight back
        // from one of its categories isn't worth another walk.
        val known = live.now.cleanerData != null
        if (!known) live.update { it.copy(cleanerLoading = true) }
        if (!known || SystemClock.elapsedRealtime() - walkedAt > FreshMillis) startWalk()
    }

    /** Files have gone since the last walk, so the next visit walks again. */
    fun outdate() {
        walkedAt = 0L
    }

    /** What the walk knows, for a category that can't be listed without it, or null for one that can. */
    suspend fun dataFor(category: String): CleanerData? {
        if (!repository.needsCleanerScan(category)) return null
        // A walk under way still has duplicates to find, so its answer is the one to wait for.
        return runningWalk()?.await() ?: live.now.cleanerData ?: startWalk().await()
    }

    fun refreshTotals() {
        if (!live.now.hasStoragePermission) return
        totals?.cancel()
        totals = scope.launch(Dispatchers.IO) {
            try {
                val counted = repository.getCategoryTotals()
                live.update { it.copy(categoryTotals = counted) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(Tag, "Couldn't add up the categories", e)
            }
        }
    }

    fun chooseCategory(category: String?) =
        live.update { it.copy(currentCleanerCategory = category, cleanerSelectedFiles = persistentSetOf()) }

    fun selectAll(ids: List<Int>) = live.update { it.copy(cleanerSelectedFiles = ids.toImmutableSet()) }

    fun selectNone() = live.update { it.copy(cleanerSelectedFiles = persistentSetOf()) }

    fun toggle(id: Int) = live.update { it.copy(cleanerSelectedFiles = it.cleanerSelectedFiles.toggled(id)) }

    private fun runningWalk(): Deferred<CleanerData>? = walk?.takeIf { it.isActive }

    /** The walk is long, so a second caller shares the one under way. Three at once ran the heap out. */
    private fun startWalk(): Deferred<CleanerData> = synchronized(this) {
        runningWalk() ?: scope.async(Dispatchers.IO) { walkPhone() }.also { walk = it }
    }

    private suspend fun walkPhone(): CleanerData {
        try {
            val found = repository.scanDeviceForCleaner(onWalked = ::showBeforeDuplicates)
            walkedAt = SystemClock.elapsedRealtime()
            live.update { it.copy(cleanerData = found) }
            return found
        } finally {
            live.update { it.copy(cleanerLoading = false) }
        }
    }

    /** Doesn't blank what's known about duplicates while they're checked again. */
    private fun showBeforeDuplicates(walked: CleanerData) = live.update {
        val duplicates = it.cleanerData?.duplicates ?: walked.duplicates
        it.copy(cleanerData = walked.copy(duplicates = duplicates), cleanerLoading = false)
    }

    /**
     * Comparing duplicates reads whole files, which is no use to anyone who's gone. A moment's grace first,
     * because leaving for the Duplicates list also closes this screen, and that list wants the answer.
     */
    private fun stopUnlessAwaited() {
        scope.launch {
            delay(GraceMillis)
            val state = live.now
            val listWaits = Places.categoryIn(state.location)?.let(repository::needsCleanerScan) == true
            if (!state.showCleanerScreen && !listWaits) walk?.cancel()
        }
    }

    private companion object {
        const val Tag = "Cleaner"

        /** How long the last walk is trusted, as long as nothing has been deleted since. */
        const val FreshMillis = 60_000L
        const val GraceMillis = 800L
    }
}
