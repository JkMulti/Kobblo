package com.kobblo.gallery.file.explorer.tools.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.OverScroller
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.max

/**
 * The editing surface. A platform EditText rather than a Compose text field, because Compose lays out
 * the whole text again on every keystroke and this one only redoes the paragraph that changed, which
 * is the difference between usable and not at a few hundred kilobytes.
 *
 * Adds what EditText lacks: a line number gutter, flinging, a read-only mode that keeps selection,
 * and a change feed for undo.
 */
@SuppressLint("ViewConstructor")
internal class CodeEditText(context: Context) : EditText(context) {

    /** Receives every change to the text made by the user. */
    var onEdit: ((TextEdit) -> Unit)? = null

    /** Told after anything changes, including undo. */
    var onChanged: (() -> Unit)? = null

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val gutterFill = Paint()
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var dragStartY = 0f
    private var dragged = false

    private var applying = false
    private var loading = false
    private var pendingStart = 0
    private var pendingRemoved = ""
    private val basePaddingStart = (12 * resources.displayMetrics.density).toInt()
    private val basePaddingVertical = (10 * resources.displayMetrics.density).toInt()

    // A line's number is the count of line breaks before it. Counting from the top on every draw
    // would be slow in a long file, so each draw counts on from where the last one stopped.
    private var cachedOffset = 0
    private var cachedLine = 0
    private var totalLines = 1

    var showLineNumbers: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateGutter()
        }

    var isReadOnly: Boolean = false
        set(value) {
            field = value
            isCursorVisible = !value
            showSoftInputOnFocus = !value
        }

    init {
        gravity = Gravity.TOP or Gravity.START
        background = null
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(0f, 1.15f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        // Plain measuring keeps long documents responsive, and hyphens have no place in code.
        breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        isVerticalScrollBarEnabled = true
        isFocusableInTouchMode = true
        setPadding(basePaddingStart, basePaddingVertical, basePaddingStart, basePaddingVertical)
        customSelectionActionModeCallback = ReadOnlyAwareSelectionMenu()
        // Keeps what's there when read-only, except for this view's own loading and undo.
        filters = arrayOf(InputFilter { _, _, _, dest, dstart, dend ->
            if (isReadOnly && !applying) dest.subSequence(dstart, dend) else null
        })
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (applying) return
                pendingStart = start
                pendingRemoved = s.subSequence(start, start + count).toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (applying) return
                onEdit?.invoke(TextEdit(pendingStart, pendingRemoved, s.subSequence(start, start + count).toString()))
            }

            override fun afterTextChanged(s: Editable) {
                if (loading) return
                // Any edit before the remembered position would make its line number wrong.
                cachedOffset = 0
                cachedLine = 0
                totalLines = countLines(s)
                updateGutter()
                onChanged?.invoke()
            }
        })
    }

    /**
     * Fills the view with no undo step. Laying out a whole file at once blocks the main thread for
     * seconds at a megabyte, while adding it a piece per frame keeps the screen responsive, and the
     * top of the file readable, the whole time.
     */
    suspend fun load(text: String) {
        applying = true
        loading = true
        try {
            var offset = 0
            setText("")
            while (offset < text.length) {
                var end = minOf(offset + LOAD_CHUNK_CHARS, text.length)
                val lineEnd = text.indexOf('\n', end)
                if (lineEnd in end until end + LOAD_CHUNK_CHARS) end = lineEnd + 1
                this.text?.append(text, offset, end)
                offset = end
                if (offset < text.length) withFrameNanos { }
            }
            setSelection(0)
        } finally {
            applying = false
            loading = false
        }
        totalLines = countLines(text)
        updateGutter()
    }

    /** Applies several replacements as one, a small piece of layout at a time, with no undo step. */
    suspend fun replaceAll(replacements: List<Pair<IntRange, String>>) {
        val target = text ?: return
        applying = true
        // Recounting the lines after each of thousands of changes would take minutes.
        loading = true
        try {
            var lastYield = android.os.SystemClock.uptimeMillis()
            // From the end, so earlier ranges stay valid.
            for ((range, value) in replacements.asReversed()) {
                target.replace(range.first, range.last + 1, value)
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastYield > 12) {
                    withFrameNanos { }
                    lastYield = android.os.SystemClock.uptimeMillis()
                }
            }
        } finally {
            applying = false
            loading = false
        }
        cachedOffset = 0
        cachedLine = 0
        totalLines = countLines(target)
        updateGutter()
    }

    /** Applies an undo or redo step. */
    fun apply(edit: TextEdit) {
        val target = text ?: return
        val end = (edit.start + edit.removed.length).coerceAtMost(target.length)
        applying = true
        target.replace(edit.start.coerceAtMost(end), end, edit.inserted)
        applying = false
        val caret = (edit.start + edit.inserted.length).coerceAtMost(target.length)
        setSelection(caret)
    }

    private var appliedColors: List<Int>? = null

    fun setColors(ink: Int, lineNumber: Int, gutter: Int, selection: Int, accent: Int) {
        // Called on every recomposition, and that means every keystroke.
        val colors = listOf(ink, lineNumber, gutter, selection, accent)
        if (colors == appliedColors) return
        appliedColors = colors
        setTextColor(ink)
        highlightColor = selection
        gutterPaint.color = lineNumber
        gutterFill.color = gutter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cursor = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent)
                setSize((2 * resources.displayMetrics.density).toInt(), 0)
            }
            textCursorDrawable = cursor
            textSelectHandle?.setTint(accent)
            textSelectHandleLeft?.setTint(accent)
            textSelectHandleRight?.setTint(accent)
        }
        invalidate()
    }

    /** Wrapping off keeps each line on one row of the screen, and side-scrolls instead. */
    fun setWrap(wrap: Boolean) {
        setHorizontallyScrolling(!wrap)
        scrollTo(0, scrollY)
    }

    /** Moves the caret to the start of a 1-based line, and returns whether the line exists. */
    fun goToLine(line: Int): Boolean {
        val content = text ?: return false
        if (line < 1) return false
        var remaining = line - 1
        var offset = 0
        while (remaining > 0) {
            val next = content.indexOf('\n', offset)
            if (next < 0) return false
            offset = next + 1
            remaining--
        }
        setSelection(offset)
        return true
    }

    private fun countLines(content: CharSequence): Int {
        var lines = 1
        for (i in 0 until content.length) if (content[i] == '\n') lines++
        return lines
    }

    private fun updateGutter() {
        val start = if (showLineNumbers) {
            val digits = max(2, totalLines.toString().length)
            gutterPaint.textSize = textSize * 0.85f
            basePaddingStart + (gutterPaint.measureText("0") * (digits + 1)).toInt()
        } else {
            basePaddingStart
        }
        if (paddingLeft != start) setPadding(start, basePaddingVertical, basePaddingStart, basePaddingVertical)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showLineNumbers) return
        val currentLayout = layout ?: return
        val content = text ?: return
        val gutterRight = paddingLeft - basePaddingStart / 2f
        canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), scrollX + gutterRight, (scrollY + height).toFloat(), gutterFill)

        val top = scrollY - paddingTop
        val first = currentLayout.getLineForVertical(top.coerceAtLeast(0))
        val last = currentLayout.getLineForVertical(top + height)
        val firstStart = currentLayout.getLineStart(first)
        // How many lines came before the first one to get a number. A row that continues a wrapped
        // line belongs to a line whose number has scrolled away, so the next number is one further.
        var lineNumber = lineNumberAt(content, firstStart) + if (firstStart == 0 || content[firstStart - 1] == '\n') 0 else 1
        val baseShift = paddingTop.toFloat()
        val x = scrollX + gutterRight - basePaddingStart / 4f
        gutterPaint.textAlign = Paint.Align.RIGHT
        for (row in first..last) {
            val start = currentLayout.getLineStart(row)
            val startsLogicalLine = start == 0 || content[start - 1] == '\n'
            if (startsLogicalLine) {
                canvas.drawText((lineNumber + 1).toString(), x, currentLayout.getLineBaseline(row) + baseShift, gutterPaint)
                lineNumber++
            }
        }
    }

    /** How many line breaks come before [offset]. */
    private fun lineNumberAt(content: CharSequence, offset: Int): Int {
        var line = cachedLine
        if (offset >= cachedOffset) {
            for (i in cachedOffset until offset) if (content[i] == '\n') line++
        } else {
            for (i in offset until cachedOffset) if (content[i] == '\n') line--
        }
        cachedOffset = offset
        cachedLine = line
        return line
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                velocity?.addMovement(event)
                dragStartY = event.y
                dragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (abs(event.y - dragStartY) > ViewConfiguration.get(context).scaledTouchSlop) dragged = true
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000, maxFling.toFloat())
                val speed = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                if (dragged && abs(speed) > minFling) fling(-speed.toInt())
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fling(speed: Int) {
        val currentLayout = layout ?: return
        val maxY = (currentLayout.height + paddingTop + paddingBottom - height).coerceAtLeast(0)
        scroller.fling(scrollX, scrollY, 0, speed, scrollX, scrollX, 0, maxY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        } else {
            super.computeScroll()
        }
    }

    // Moving the caret in response to a tap must not stop a fling from the system's scroll logic.
    override fun bringPointIntoView(offset: Int): Boolean {
        if (!scroller.isFinished) return false
        return super.bringPointIntoView(offset)
    }

    /** In read-only mode the selection menu keeps Copy and loses what would change the text. */
    private inner class ReadOnlyAwareSelectionMenu : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (isReadOnly) {
                menu.removeItem(android.R.id.cut)
                menu.removeItem(android.R.id.paste)
                menu.removeItem(android.R.id.pasteAsPlainText)
                menu.removeItem(android.R.id.replaceText)
            }
            return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private companion object {
        const val LOAD_CHUNK_CHARS = 16 * 1024
    }
}
