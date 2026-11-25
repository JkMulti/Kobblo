package com.kobblo.gallery.file.explorer.fileops

/**
 * Every command the app hands to a shell is built here. File names come from anywhere, including other
 * people's phones, and that shell can have far more rights than the app does. Double quotes leave `$(...)`
 * and backticks alive, so each argument goes in single quotes, where a shell gives nothing a special meaning.
 *
 * Paths from the app are absolute, but `--` still ends the options, so no name can ever read as one.
 */
object ShellCommand {
    /** A package name is letters, digits and underscores in at least two dotted parts. */
    private val PackageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Makes [argument] one literal word for `sh`, whatever is in it. A NUL can't be passed to a program at all. */
    fun quote(argument: String): String {
        require(argument.indexOf('\u0000') < 0) { "A shell argument can't contain NUL" }
        return "'" + argument.replace("'", "'\\''") + "'"
    }

    fun listFolder(path: String): String = "ls -1Ap -- ${quote(path)} 2>/dev/null"

    /** Refuses the empty path and the root, which no caller means and nothing could undo. */
    fun deleteTree(path: String): String {
        require(path.isNotBlank() && path.trimEnd('/').isNotEmpty()) { "Refusing to delete '$path'" }
        return "rm -rf -- ${quote(path)}"
    }

    fun copyTree(from: String, to: String): String = "cp -r -- ${quote(from)} ${quote(to)}"

    /** Fails rather than replacing, because a plain `mv` overwrites without a word. */
    fun moveTo(from: String, to: String): String =
        "[ ! -e ${quote(to)} ] && [ ! -L ${quote(to)} ] && mv -- ${quote(from)} ${quote(to)}"

    fun makeFolders(path: String): String = "mkdir -p -- ${quote(path)}"

    fun exists(path: String): String = "[ -e ${quote(path)} ] || [ -L ${quote(path)} ]"

    fun installApk(path: String, sizeBytes: Long, allowDowngrade: Boolean): String =
        "cat -- ${quote(path)} | cmd package install -r ${if (allowDowngrade) "-d " else ""}-S $sizeBytes"

    /** Null for anything that isn't a package name. The name is read out of an APK someone else made. */
    fun uninstallApk(packageName: String): String? =
        if (PackageName.matches(packageName)) "cmd package uninstall ${quote(packageName)}" else null
}
