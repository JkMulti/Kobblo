package com.kobblo.gallery.file.explorer

import android.content.Context
import android.util.Log
import com.kobblo.gallery.file.explorer.fileops.BinDeleteReport
import com.kobblo.gallery.file.explorer.fileops.BinMoveReport
import com.kobblo.gallery.file.explorer.fileops.BinRestoreReport
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.RenameProblem
import com.kobblo.gallery.file.explorer.fileops.SettledJob
import com.kobblo.gallery.file.explorer.fileops.TransferEvents
import com.kobblo.gallery.file.explorer.fileops.TransferReport
import com.kobblo.gallery.file.explorer.files.AndroidRecentWords
import com.kobblo.gallery.file.explorer.files.AndroidStorage
import com.kobblo.gallery.file.explorer.files.ApkInstaller
import com.kobblo.gallery.file.explorer.files.Bin
import com.kobblo.gallery.file.explorer.files.CategoryList
import com.kobblo.gallery.file.explorer.files.CategoryLists
import com.kobblo.gallery.file.explorer.files.CleanerScan
import com.kobblo.gallery.file.explorer.files.DetailsReader
import com.kobblo.gallery.file.explorer.files.DriveLocation
import com.kobblo.gallery.file.explorer.files.FavoriteLists
import com.kobblo.gallery.file.explorer.files.FileDeletion
import com.kobblo.gallery.file.explorer.files.FileEdits
import com.kobblo.gallery.file.explorer.files.FolderLists
import com.kobblo.gallery.file.explorer.files.GalleryReader
import com.kobblo.gallery.file.explorer.files.LocalRows
import com.kobblo.gallery.file.explorer.files.LocksAndFavorites
import com.kobblo.gallery.file.explorer.files.MediaFacts
import com.kobblo.gallery.file.explorer.files.Places
import com.kobblo.gallery.file.explorer.files.RecentHistory
import com.kobblo.gallery.file.explorer.files.RecentLists
import com.kobblo.gallery.file.explorer.files.SavedChoices
import com.kobblo.gallery.file.explorer.files.ShellFolders
import com.kobblo.gallery.file.explorer.files.ShizukuShell
import com.kobblo.gallery.file.explorer.files.SystemIndex
import com.kobblo.gallery.file.explorer.files.TransferRequest
import com.kobblo.gallery.file.explorer.files.Transfers
import com.kobblo.gallery.file.explorer.files.drive.DriveAccounts
import com.kobblo.gallery.file.explorer.files.drive.DriveFiles
import com.kobblo.gallery.file.explorer.files.drive.DriveFolders
import com.kobblo.gallery.file.explorer.files.drive.DriveTransfer
import com.kobblo.gallery.file.explorer.files.mustHandle
import com.kobblo.gallery.file.explorer.gallery.Favorites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Where the rest of the app asks for files: on the phone, on its memory card and on Google Drive. Each kind of
 * work is done by a class in the `files` package. This puts those together, and sees that whatever waits for a
 * disk or a network does so on a thread that may.
 *
 * Making one is cheap and reads nothing but preferences, so any screen or job can have its own.
 */
class FileRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE)
    private val choices = SavedChoices(context, prefs)
    private val history = RecentHistory(prefs)

    private val storage = AndroidStorage(context)
    private val shell = ShizukuShell()
    private val index = SystemIndex(context)
    private val followers = LocksAndFavorites(context)
    private val apks = ApkInstaller(context.packageManager, shell)

    private val accounts = DriveAccounts(context, prefs)
    private val driveFiles = DriveFiles(accounts::connection) { context.cacheDir }
    private val driveFolders = DriveFolders { context.getString(R.string.app_unknown) }
    private val driveTransfer = DriveTransfer({ accounts.connection() }, shell) { context.cacheDir }

    private val rows = LocalRows { count -> context.resources.getQuantityString(R.plurals.items_count, count, count) }
    private val folderLists = FolderLists(storage, rows, ShellFolders(shell), index::folder) { path ->
        Log.e("KobbloStorage", "listFiles() returned null for '$path'. Storage access may not be granted.")
        IOException(context.getString(R.string.storage_access_denied_or_error))
    }
    private val favoriteLists = FavoriteLists(rows)
    private val recentLists = RecentLists(history::entries, storage, rows, AndroidRecentWords(context), { MediaFacts.ofRecording(it).duration })
    private val categoryLists = CategoryLists(index, storage, choices::marksInLists)
    private val gallery = GalleryReader(context, choices)
    private val details = DetailsReader()

    private val edits = FileEdits(storage, shell, followers)
    private val deletion = FileDeletion(storage, shell, index, followers)
    private val transfers = Transfers(storage, shell, driveTransfer, index, followers)

    /** The Bin's folder. It's hidden, and what's in it is the Bin's business. */
    val trashDir = File(storage.root, ".kobblo_trash")
    private val bin = Bin(trashDir, storage, shell, index, followers, deletion::removeByOtherRoute)

    init {
        accounts.connectAll()
    }

    // Lists

    /**
     * What's at [location]: a folder's path, "home" or "sd" for the top of a storage, "recent", "pinned" for
     * Favorites, or a place on Google Drive. Throws for a folder that can't be opened at all.
     */
    suspend fun getFiles(location: String): List<FileItem> = withContext(Dispatchers.IO) {
        val onDrive = DriveLocation.of(location)
        when {
            onDrive != null ->
                accounts.connection(onDrive.account)?.let { driveFolders.list(it, onDrive, choices.marksInLists()) }.orEmpty()
            location == Places.Favorites -> favoriteLists.list(choices.marksInLists())
            location == Places.Recent -> recentLists.list(choices.marksInLists())
            else -> folderLists.list(location, if (location.startsWith("/")) choices.marksInFolder() else choices.marksInLists())
        }
    }

    /** The list row's item for one file, for screens that start from a path. */
    fun itemFor(file: File): FileItem = rows.row(file, file.path.hashCode(), choices.marksInLists())

    suspend fun getFileDetails(path: String): FileDetails = withContext(Dispatchers.IO) { details.read(path) }

    /** Every photo and video the system knows about, newest first, for the Home tab's feed. */
    fun getGalleryMedia(showHidden: Boolean): List<FileItem> = gallery.everything(showHidden)

    /**
     * Just the newest [limit] rows, which is all the first screens of the feed need and takes a fraction
     * of the time of a large library. The second value says whether that was the whole library anyway.
     */
    fun getNewestGalleryMedia(showHidden: Boolean, limit: Int): Pair<List<FileItem>, Boolean> = gallery.newest(showHidden, limit)

    /** What was done to a file or folder, for Recents. */
    fun logRecentAction(path: String, action: String) = history.add(path, action)

    // Categories and the Cleaner

    /** The numbers beside the Browse tab's categories. */
    suspend fun getCategoryTotals(): CategoryTotals = withContext(Dispatchers.IO) { categoryLists.totals() }

    /** The files of one category. [cleanerData] is only read for the lists that [needsCleanerScan] names. */
    suspend fun getCategoryFiles(categoryKey: String, cleanerData: CleanerData?): List<FileItem> =
        withContext(Dispatchers.IO) { categoryLists.files(categoryKey, cleanerData) }

    /** Whether a category's list takes a walk of the disk, rather than coming from the system's index. */
    fun needsCleanerScan(categoryKey: String): Boolean = CategoryList.takesAWalk(categoryKey)

    /** Walks the disk for the Cleaner. [onWalked] gets everything but the duplicates, which take longer, first. */
    suspend fun scanDeviceForCleaner(onWalked: (CleanerData) -> Unit = {}): CleanerData = withContext(Dispatchers.IO) {
        CleanerScan(storage, choices::marksInLists) { File(it).canWrite() || shell.mustHandle(it) }.run(onWalked)
    }

    // Making, renaming, copying and moving

    /** False when it isn't there afterwards, or was there before. */
    suspend fun createFolder(parentPath: String, name: String): Boolean = withContext(Dispatchers.IO) {
        create(parentPath, { driveFiles.createFolder(it, name) }, { edits.createFolder(it, name) })
    }

    suspend fun createFile(parentPath: String, name: String): Boolean = withContext(Dispatchers.IO) {
        create(parentPath, { driveFiles.createFile(it, name) }, { edits.createFile(it, name) })
    }

    private inline fun create(location: String, onDrive: (DriveLocation) -> Boolean, onPhone: (String) -> Boolean): Boolean {
        if (Places.isOnlyAList(location)) return false
        return DriveLocation.of(location)?.let(onDrive) ?: onPhone(location)
    }

    suspend fun renameFile(path: String, newName: String): Boolean = renameChecked(path, newName) == null

    /** Null when it's done, or else why it wasn't. */
    suspend fun renameChecked(path: String, newName: String): RenameProblem? = withContext(Dispatchers.IO) {
        when (val id = DriveLocation.idIn(path)) {
            null -> edits.rename(path, newName)
            else -> if (driveFiles.rename(id, newName)) null else RenameProblem.FAILED
        }
    }

    internal suspend fun copyFiles(
        sourcePaths: List<String>,
        destDir: String,
        onProgress: (Float) -> Unit = {},
        checkPause: suspend () -> Unit = {},
        events: TransferEvents = TransferEvents.None
    ): TransferReport = withContext(Dispatchers.IO) {
        transfers.run(sourcePaths, destDir, TransferRequest(move = false, onProgress, checkPause, events))
    }

    internal suspend fun moveFiles(
        sourcePaths: List<String>,
        destDir: String,
        onProgress: (Float) -> Unit = {},
        checkPause: suspend () -> Unit = {},
        events: TransferEvents = TransferEvents.None
    ): TransferReport = withContext(Dispatchers.IO) {
        transfers.run(sourcePaths, destDir, TransferRequest(move = true, onProgress, checkPause, events))
    }

    // Deleting, and the Bin

    /** Takes a file out for good. On the thread it's called from it could stop the app for a large folder. */
    suspend fun deleteRestrictedPath(path: String): Boolean = withContext(Dispatchers.IO) { deletion.delete(path) }

    /** Deletes many for good and says which went. [onProgress] gets how many paths have been dealt with so far. */
    suspend fun deleteAllRestricted(
        paths: List<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) { deletion.deleteAll(paths, shouldStop, onProgress) }

    /** Says what really moved. [onProgress] gets how many paths have been dealt with so far. */
    suspend fun moveToTrash(
        paths: List<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): BinMoveReport = withContext(Dispatchers.IO) { bin.moveIn(paths, shouldStop, onProgress) }

    suspend fun restoreFromTrash(
        encodedNames: List<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): BinRestoreReport = withContext(Dispatchers.IO) { bin.restore(encodedNames, shouldStop, onProgress) }

    suspend fun permanentlyDeleteTrash(
        encodedNames: List<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): BinDeleteReport = withContext(Dispatchers.IO) { bin.deleteForever(encodedNames, shouldStop, onProgress) }

    /** What's in the Bin, once whatever is older than [expiryTimeMs] has been cleared out. Zero keeps everything. */
    suspend fun getTrashFiles(expiryTimeMs: Long = 7L * 24 * 60 * 60 * 1000L): List<FileItem> =
        withContext(Dispatchers.IO) { bin.list(expiryTimeMs, choices.marksInLists()) }

    /** Where something in the Bin is, by the name it has there. */
    internal fun binPathOf(encodedName: String): String = bin.pathOf(encodedName)

    /** Tells the index, and any lock, what a job killed partway really did. */
    internal fun settleAfterKill(job: SettledJob) {
        job.moved.forEach { (from, to) -> followers.moved(from, to) }
        when (job.kind) {
            JobKind.TRASH, JobKind.DELETE -> index.forget(job.gone)
            else -> index.announce(job.outputs + job.gone)
        }
    }

    // Google Drive

    val currentGoogleAccountEmail: String?
        get() = accounts.current

    fun getGoogleAccounts(): List<String> = accounts.all()

    /** Switches to an account that has already been added. */
    fun setActiveGoogleAccount(email: String) = accounts.switchTo(email)

    /** Adds an account and switches to it. Null means that none is in use any more. */
    fun setGoogleAccount(email: String?) {
        if (email != null) accounts.add(email) else accounts.useNone()
    }

    fun removeGoogleAccount(email: String) = accounts.remove(email)

    /** Files the person chose to share with the app. While there are any, the top of Drive shows only those. */
    var currentDrivePickedIds: Set<String>? by driveFolders::pickedIds

    /** How much of an account is used and how much there is, in bytes, or null if it can't be found out. */
    suspend fun getDriveStorageQuota(email: String? = null): Pair<Long, Long>? =
        withContext(Dispatchers.IO) { driveFiles.quota(email) }

    /** Null when it's gone, or else what went wrong. */
    suspend fun deleteDriveFile(fileId: String): String? = withContext(Dispatchers.IO) { driveFiles.delete(fileId) }

    suspend fun downloadDriveFileToLocal(fileId: String, destination: File, email: String? = null): Result<File> =
        withContext(Dispatchers.IO) { driveFiles.download(fileId, destination, email) }

    suspend fun uploadLocalFileToDrive(localFile: File, destParentLocation: String, email: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) { driveFiles.upload(localFile, destParentLocation, email) }

    /** Puts a file in the cache to be opened, and says where. Null if that can't be done. */
    suspend fun downloadDriveFile(fileId: String, fileName: String, email: String? = null): File? =
        withContext(Dispatchers.IO) { driveFiles.downloadToCache(fileId, fileName, email) }

    // Favorites, locks and the Vault

    fun getPinnedFiles(): Set<String> = choices.favorites

    fun togglePin(path: String) = Favorites.toggle(context, path)

    fun getLockedFiles(): Set<String> = choices.locks

    fun setLocked(path: String, locked: Boolean) = choices.setLocked(path, locked)

    fun isVaultAlwaysShown(): Boolean = choices.vaultAlwaysShown

    fun setVaultAlwaysShown(alwaysShown: Boolean) {
        choices.vaultAlwaysShown = alwaysShown
    }

    // Shizuku

    fun hasShizuku(): Boolean = shell.isReady

    /** The next answer from [hasShizuku] is a new one. */
    fun invalidateShizukuCache() = shell.forgetAnswer()

    fun getPackageNameFromApk(path: String): String? = apks.packageNameIn(path)

    /** Whether it worked, and what the installer said. */
    fun installApkSilent(path: String, downgrade: Boolean): Pair<Boolean, String> = apks.install(path, downgrade)

    fun uninstallApkSilent(packageName: String): Pair<Boolean, String> = apks.uninstall(packageName)
}
