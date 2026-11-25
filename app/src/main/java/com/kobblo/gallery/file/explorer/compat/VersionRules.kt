package com.kobblo.gallery.file.explorer.compat

/**
 * The decisions that change with the Android version, as plain functions of it. Only Android 14 and 16 have ever
 * run this app, so for everything older these tests and a careful reading are all the evidence there is.
 */
internal object VersionRules {
    private const val Q = 29
    private const val R = 30
    private const val T = 33

    /** Where asking for storage access has to send someone. */
    enum class StorageRoute {
        /** Android 11 and up: only the Settings page for all files access can grant it. */
        AllFilesSettings,

        /** Android 8 to 10: the system's own dialog. */
        SystemDialog,

        /** Android 8 to 10 after "don't ask again": the dialog would answer no without showing. */
        AppSettings
    }

    fun storageRoute(sdk: Int, askedBefore: Boolean, systemWouldExplain: Boolean): StorageRoute = when {
        sdk >= R -> StorageRoute.AllFilesSettings
        !askedBefore || systemWouldExplain -> StorageRoute.SystemDialog
        else -> StorageRoute.AppSettings
    }

    /**
     * Whether coming back to the app should raise the system's dialog by itself. Answering it brings the app back,
     * so asking on every return would put a refused dialog straight back up, forever. Once per launch is the limit.
     */
    fun asksForStorageOnResume(sdk: Int, askedThisLaunch: Boolean): Boolean = sdk < R && !askedThisLaunch

    /** Notifications are off until asked for from Android 13. Below that there's nothing to ask, and it's asked once. */
    fun asksForNotifications(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdk >= T && !granted && !askedBefore

    /** Before Android 10 the video table has no orientation, and asking for one fails the whole query. */
    fun mediaTableHasOrientation(sdk: Int, isVideoTable: Boolean): Boolean = !isVideoTable || sdk >= Q

    /**
     * Rows to step over by hand for a page starting at [offset]. Before Android 11 the system's index ignores a
     * query's limit and offset and hands back everything, so paging has to happen on this side of the cursor.
     */
    fun rowsToSkip(offset: Int, providerApplied: Boolean): Int = if (providerApplied) 0 else offset

    /** Whether a provider says it used the limit. Android 11's index does what it's asked, whether or not it says so. */
    fun providerAppliedLimit(sdk: Int, honoredArgs: Array<String>?, limitArg: String): Boolean =
        sdk >= R || honoredArgs?.contains(limitArg) == true
}
