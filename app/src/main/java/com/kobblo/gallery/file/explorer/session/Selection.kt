package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet

/**
 * What's chosen in the list on screen, by its rows' ids. Nothing locked can be, however it's asked for: what
 * can be selected can be shared, sent and copied without a password.
 */
internal class Selection(private val live: LiveState) {

    fun toggle(id: Int) = live.update { state ->
        val adding = id !in state.selectedFiles
        if (adding && state.files.any { it.id == id && it.isLocked }) state
        else state.choosing(state.selectedFiles.toggled(id))
    }

    /** Chooses [ids], or everything listed. */
    fun all(ids: Collection<Int>?) = live.update { state ->
        val locked = state.files.filter { it.isLocked }.mapTo(HashSet()) { it.id }
        val wanted = ids ?: state.files.map { it.id }
        state.choosing(wanted.filterNot { it in locked }.toImmutableSet())
    }

    fun none() = live.update { it.choosingNothing() }

    /** The files chosen, in the list's order. */
    fun files(): List<FileItem> {
        val state = live.now
        return state.files.filter { it.id in state.selectedFiles }
    }

    private fun AppState.choosing(ids: ImmutableSet<Int>) = copy(selectedFiles = ids, isSelectionMode = ids.isNotEmpty())
}

internal fun AppState.choosingNothing() = copy(selectedFiles = persistentSetOf(), isSelectionMode = false)

/** These ids with [id] taken out if it's in them, or put in if it isn't. */
internal fun ImmutableSet<Int>.toggled(id: Int): ImmutableSet<Int> = (if (id in this) this - id else this + id).toImmutableSet()
