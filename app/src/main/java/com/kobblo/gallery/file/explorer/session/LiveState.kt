package com.kobblo.gallery.file.explorer.session

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// What the parts of the main screen's view model have in common. Each part owns one feature and takes
// these and the few other things it needs, so it can be built and tested without the rest.

/** The one copy of the app's state. Any thread may read or change it; a change can be retried, so it must only copy. */
internal class LiveState(initial: AppState) {
    private val current = MutableStateFlow(initial)

    val flow: StateFlow<AppState> = current.asStateFlow()

    val now: AppState get() = current.value

    fun update(change: (AppState) -> AppState) = current.update(change)
}

/** The app's own words, in the language it's in now. */
internal interface Words {
    fun text(@StringRes id: Int, vararg args: Any): String

    /** Words that depend on how many, such as "3 files". */
    fun counted(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

internal class AppWords(private val app: Application) : Words {
    // Asked for without arguments, a string is left exactly as it's written, percent signs and all.
    override fun text(id: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    override fun counted(id: Int, count: Int, vararg args: Any): String =
        app.resources.getQuantityString(id, count, *args)
}

/**
 * The lines shown at the foot of the screen. The screen takes them one at a time, the next once the last
 * has gone, and a line that nobody is there to show is dropped.
 */
internal class Messages(private val scope: CoroutineScope) {
    private val lines = MutableSharedFlow<String>()

    val flow: SharedFlow<String> = lines.asSharedFlow()

    /** Hands [line] over, waiting until the screen takes it. */
    suspend fun show(line: String) = lines.emit(line)

    /** Leaves [line] to be shown whenever its turn comes. */
    fun post(line: String) {
        scope.launch { show(line) }
    }
}
