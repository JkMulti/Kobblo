package com.kobblo.gallery.file.explorer.tools.editor

/** One change to a text: at [start], [removed] gave way to [inserted]. */
data class TextEdit(val start: Int, val removed: String, val inserted: String)

/**
 * Undo and redo for a text editor.
 *
 * Keystrokes merge into words so undo takes back something meaningful, and an input method rewriting
 * the word it is composing counts as one edit. Knows which state was last saved, so undoing back to
 * it clears the unsaved mark.
 */
class EditHistory(
    private val maxEdits: Int = 2000,
    private val maxChars: Int = 8_000_000,
    private val mergeWindowMs: Long = 1500
) {
    private class Entry(val id: Long, var edit: TextEdit, var at: Long)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var nextId = 1L
    private var savedId = 0L
    // The state undo can get back to once old steps have been dropped: the last one dropped.
    private var floorId = 0L
    private var mergeBlocked = false
    private var chars = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** True when the text differs from the last saved state. */
    val isDirty: Boolean get() = currentId() != savedId

    private fun currentId() = undoStack.lastOrNull()?.id ?: floorId

    fun record(edit: TextEdit, now: Long = System.currentTimeMillis()) {
        if (edit.removed == edit.inserted) return
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (last != null && !mergeBlocked && now - last.at <= mergeWindowMs) {
            val merged = merge(last.edit, edit)
            if (merged != null) {
                chars += merged.size() - last.edit.size()
                last.edit = merged
                last.at = now
                return
            }
        }
        mergeBlocked = false
        undoStack.addLast(Entry(nextId++, edit, now))
        chars += edit.size()
        trim()
    }

    /** The edit that takes the last change back: apply it to the text. */
    fun undo(): TextEdit? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        mergeBlocked = true
        return TextEdit(entry.edit.start, entry.edit.inserted, entry.edit.removed)
    }

    fun redo(): TextEdit? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        mergeBlocked = true
        return entry.edit
    }

    fun markSaved() {
        savedId = currentId()
        mergeBlocked = true
    }

    /** Forgets everything, for when the text is replaced from outside, such as a reload. */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        chars = 0
        savedId = 0
        floorId = 0
        mergeBlocked = false
    }

    /** Merges two edits in a row into one, when together they read as one action. */
    private fun merge(first: TextEdit, second: TextEdit): TextEdit? {
        // An input method replacing the word it is still composing.
        if (second.start == first.start && second.removed == first.inserted) {
            return TextEdit(first.start, first.removed, second.inserted)
        }
        // Typing on: the new text starts where the last ended. A line break starts a new undo step.
        val typingOn = first.removed.isEmpty() && second.removed.isEmpty() &&
            second.start == first.start + first.inserted.length &&
            '\n' !in second.inserted && !first.inserted.endsWith('\n')
        if (typingOn) return TextEdit(first.start, "", first.inserted + second.inserted)
        // Holding backspace: each deletion ends where the last began.
        val deletingBack = first.inserted.isEmpty() && second.inserted.isEmpty() &&
            second.start + second.removed.length == first.start &&
            '\n' !in second.removed
        if (deletingBack) return TextEdit(second.start, second.removed + first.removed, "")
        return null
    }

    private fun trim() {
        while (undoStack.size > maxEdits || (chars > maxChars && undoStack.size > 1)) {
            val dropped = undoStack.removeFirst()
            chars -= dropped.edit.size()
            floorId = dropped.id
        }
    }

    private fun TextEdit.size() = (removed.length + inserted.length).toLong()
}
