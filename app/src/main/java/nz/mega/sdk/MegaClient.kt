package nz.mega.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

private const val TAG = "MegaClient"

/**
 * One MEGA account, reached through MEGA's own SDK.
 *
 * The SDK is native code that answers on threads of its own. Each call here hands it a listener and
 * suspends until that listener hears the call is over, however long that takes: an upload has no time
 * limit, and the SDK does its own retrying underneath. Cancelling the coroutine stops the waiting but not
 * the SDK, which finishes what it started.
 *
 * An account is signed in with [login] once. [dumpSession] then gives a text that [fastLogin] takes on a
 * later run in place of the password, and either way [fetchNodes] has to follow before the account's
 * files can be listed.
 *
 * Use [getInstance]: the SDK keeps an account's state in memory and in a folder of its own, and a second
 * client for the same account would be a second SDK over that same folder.
 */
class MegaClient(
    private val context: Context,
    val accountEmail: String? = null
) {
    companion object {
        /** The app key sent with every MEGA request. A placeholder: put your own key from mega.io here. */
        private const val APP_KEY = "YOUR_KEY"
        private const val USER_AGENT = "Kobblo/1.0"

        /** The account used when none is named. */
        private const val DEFAULT_ACCOUNT = "default"

        private val clients = ConcurrentHashMap<String, MegaClient>()

        /** The one client for the account [email], made on first use and kept for the life of the process. */
        fun getInstance(context: Context, email: String?): MegaClient =
            clients.getOrPut(email ?: DEFAULT_ACCOUNT) { MegaClient(context.applicationContext, email) }
    }

    /** Created on first use, since creating it starts the SDK's threads and opens the account's folder. */
    internal val megaApi: MegaApi by lazy {
        val stateFolder = File(context.cacheDir, MegaItems.stateFolderName(accountEmail)).apply { mkdirs() }
        MegaApi(APP_KEY, stateFolder.absolutePath, USER_AGENT)
    }

    suspend fun login(email: String, password: String): Boolean =
        confirm("MEGA Login failed") { megaApi.login(email, password, it) }

    /** Signs in again with what [dumpSession] gave last time. */
    suspend fun fastLogin(session: String): Boolean =
        confirm("MEGA FastLogin failed") { megaApi.fastLogin(session, it) }

    /** The signed-in session as text to keep, or null if the SDK has none to give. */
    fun dumpSession(): String? = try {
        megaApi.dumpSession()
    } catch (_: Exception) {
        null
    }

    /** Loads the account's whole tree of files into the SDK. Listing reads from that, not from the network. */
    suspend fun fetchNodes(): Boolean =
        confirm("Failed to fetch MEGA nodes") { megaApi.fetchNodes(it) }

    /**
     * Signs out on this phone and clears what the SDK kept here, without asking MEGA to end the session.
     * An error from the SDK comes back as false, not thrown.
     */
    suspend fun localLogout(): Boolean =
        request({ megaApi.localLogout(it) }) { _, error ->
            Result.success(error == null || error.errorCode == MegaError.API_OK)
        }

    /**
     * What's in the folder with the handle [folderId], or in the top of the cloud drive for null. Empty
     * for a folder that isn't there, and for anything that goes wrong.
     */
    fun getChildren(folderId: String?): List<FileItem> = try {
        val children = folderAt(folderId)?.let { megaApi.getChildren(it) }
        if (children == null) {
            emptyList()
        } else {
            val day = DisplayDates.date()
            (0 until children.size()).mapNotNull { index -> children.get(index)?.let { describe(it, day) } }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't list a MEGA folder", e)
        emptyList()
    }

    private fun describe(node: MegaNode, day: DateFormat): FileItem {
        val isFolder = node.isFolder
        return MegaItems.item(
            handle = node.handle,
            name = node.name,
            isFolder = isFolder,
            sizeBytes = if (isFolder) 0L else node.size,
            modifiedSeconds = node.modificationTime,
            isEmptyFolder = isFolder && (megaApi.getChildren(node)?.size() ?: 0) == 0,
            owner = accountEmail ?: "me",
            day = day
        )
    }

    /** False when there's no such node. An SDK failure is thrown. */
    suspend fun deleteNode(handleStr: String): Boolean {
        val node = nodeAt(handleStr) ?: return false
        return confirm("Failed to delete MEGA node") { megaApi.remove(node, it) }
    }

    /** Bytes used and bytes allowed, in that order. Both zero when the account won't say. */
    suspend fun getStorageQuota(): Pair<Long, Long> =
        request({ megaApi.getAccountDetails(it) }) { request, error ->
            val details = request?.takeIf { error.isOk() }?.megaAccountDetails
            Result.success(if (details == null) Pair(0L, 0L) else Pair(details.storageUsed, details.storageMax))
        }

    /** Downloads the file with the handle [handleStr] to the path [destPath]. False when there's no such file. */
    suspend fun downloadFile(handleStr: String, destPath: String): Boolean {
        val node = nodeAt(handleStr) ?: return false
        return transfer("Download failed") { listener ->
            megaApi.startDownload(
                node,
                destPath,
                null, // no other name than the one in destPath
                null, // no data of the app's own to carry along
                false, // waits its turn among transfers
                null, // can't be cancelled from here
                0, // neither of these names one of the SDK's rules for a file that's already there,
                0, // so what it does then is left to the SDK
                false, // not bringing back a deleted file
                listener
            )
        }
    }

    /** Uploads the file at [localPath] into the folder [parentHandle], or the top of the cloud drive for null. */
    suspend fun uploadFile(localPath: String, parentHandle: String?): Boolean {
        val parent = folderAt(parentHandle) ?: throw Exception("MEGA upload failed: destination folder not found")
        val name = localPath.substringAfterLast('/')
        return transfer("MEGA upload failed") { listener ->
            megaApi.startUpload(
                localPath,
                parent,
                name,
                MegaApi.INVALID_CUSTOM_MOD_TIME, // the file's own modification time stands
                null, // no data of the app's own to carry along
                false, // the local file stays when it's done
                false, // waits its turn among transfers
                null, // can't be cancelled from here
                listener
            )
        }
    }

    suspend fun createFolder(name: String, parentHandle: String?): Boolean {
        val parent = folderAt(parentHandle) ?: throw Exception("MEGA create folder failed: destination folder not found")
        return confirm("MEGA create folder failed") { megaApi.createFolder(name, parent, it) }
    }

    private fun nodeAt(handle: String): MegaNode? = megaApi.getNodeByHandle(MegaItems.handleOf(handle))

    private fun folderAt(handle: String?): MegaNode? = if (handle == null) megaApi.rootNode else nodeAt(handle)

    /** A request whose only answer is whether it worked. A failure is thrown, starting with [failure]. */
    private suspend fun confirm(failure: String, start: (MegaRequestListener) -> Unit): Boolean =
        request(start) { _, error -> error.orFailure(failure) }

    /**
     * Starts a request with [start] and waits for the SDK to finish it. [read] runs on the SDK's thread,
     * inside its callback, because the request and error it's given are the SDK's and are gone once the
     * callback returns.
     */
    private suspend fun <T> request(
        start: (MegaRequestListener) -> Unit,
        read: (MegaRequest?, MegaError?) -> Result<T>
    ): T = suspendCancellableCoroutine { waiting ->
        val listener = object : MegaRequestListener() {
            override fun onRequestFinish(api: MegaApi?, request: MegaRequest?, e: MegaError?) =
                InFlight.finish(this, waiting) { read(request, e) }
        }
        InFlight.begin(listener, waiting) { start(listener) }
    }

    /** As [confirm], for an upload or a download, which the SDK reports through a listener of another kind. */
    private suspend fun transfer(failure: String, start: (MegaTransferListener) -> Unit): Boolean =
        suspendCancellableCoroutine { waiting ->
            val listener = object : MegaTransferListener() {
                override fun onTransferFinish(api: MegaApi?, transfer: MegaTransfer?, error: MegaError?) =
                    InFlight.finish(this, waiting) { error.orFailure(failure) }
            }
            InFlight.begin(listener, waiting) { start(listener) }
        }
}

/** The SDK always sends an error object, and "no error" is one of its codes. */
private fun MegaError?.isOk(): Boolean = this != null && errorCode == MegaError.API_OK

private fun MegaError?.orFailure(failure: String): Result<Boolean> =
    if (isOk()) Result.success(true) else Result.failure(Exception("$failure: ${this?.errorString ?: "Unknown error"}"))

/**
 * The listeners of calls the SDK hasn't finished yet.
 *
 * The native side doesn't keep a listener alive. One that nothing here still pointed to could be collected
 * in the middle of its call, and the SDK would then call back into freed memory and take the app down with
 * it. So each is held from the moment its call starts until the SDK reports the end.
 */
private object InFlight {
    /** Past this many at once, the old ones are looked over. */
    private const val MANY = 20

    /** A call still held after this long is taken to be one the SDK will never report on. */
    private const val GIVEN_UP_AFTER_MILLIS = 15 * 60 * 1000L

    private val heldSince = ConcurrentHashMap<Any, Long>()

    /** Holds [listener] and runs [start], which hands it to the SDK. If that throws, the wait ends with it. */
    fun <T> begin(listener: Any, waiting: CancellableContinuation<T>, start: () -> Unit) {
        hold(listener)
        try {
            start()
        } catch (problem: Throwable) {
            heldSince.remove(listener)
            if (waiting.isActive) waiting.resumeWithException(problem)
        }
    }

    /**
     * Ends the wait with what [read] makes of the SDK's answer, and lets [listener] go. A wait that was
     * cancelled meanwhile has nobody to tell, so the answer isn't read at all.
     */
    fun <T> finish(listener: Any, waiting: CancellableContinuation<T>, read: () -> Result<T>) {
        try {
            if (waiting.isActive) waiting.resumeWith(read())
        } catch (problem: Throwable) {
            Log.e(TAG, "Couldn't take in the SDK's answer", problem)
            if (waiting.isActive) waiting.resumeWithException(problem)
        } finally {
            heldSince.remove(listener)
        }
    }

    private fun hold(listener: Any) {
        val now = SystemClock.elapsedRealtime()
        heldSince[listener] = now
        if (heldSince.size > MANY) heldSince.entries.removeAll { now - it.value > GIVEN_UP_AFTER_MILLIS }
    }
}

/** How MEGA's nodes and accounts are written down in Kobblo. Free of the SDK, so it can be checked without it. */
internal object MegaItems {
    private val NotForFolderNames = Regex("[^a-zA-Z0-9._-]")

    /**
     * A handle is a number the app carries around as text. Text that isn't one becomes 0, a handle the
     * SDK has nothing for.
     */
    fun handleOf(text: String): Long = text.toLongOrNull() ?: 0L

    /** The folder under the cache that holds one account's SDK state: its session, and its tree of files. */
    fun stateFolderName(email: String?): String =
        if (email.isNullOrBlank()) "mega_default" else "mega_" + email.replace(NotForFolderNames, "_")

    /**
     * The list row for one node. A file's [FileItem.type] is its extension exactly as it's written, not
     * one of the kinds the phone's own files get, and where a file has its size a folder reads "Folder".
     *
     * @param modifiedSeconds since 1970, as the SDK counts time.
     */
    fun item(
        handle: Long,
        name: String?,
        isFolder: Boolean,
        sizeBytes: Long,
        modifiedSeconds: Long,
        isEmptyFolder: Boolean,
        owner: String,
        day: DateFormat
    ): FileItem {
        val modified = modifiedSeconds * 1000
        return FileItem(
            id = handle.hashCode(),
            path = "mega_id:$handle",
            name = name ?: "Unknown",
            type = if (isFolder) "folder" else name?.substringAfterLast(".", "").orEmpty(),
            kind = if (isFolder) "Folder" else "File",
            size = if (isFolder) "Folder" else FileSizes.label(sizeBytes),
            changed = day.format(Date(modified)),
            owner = owner,
            isPinned = false,
            isEmptyFolder = isEmptyFolder,
            sizeBytes = if (isFolder) 0L else sizeBytes,
            lastModified = modified
        )
    }
}
