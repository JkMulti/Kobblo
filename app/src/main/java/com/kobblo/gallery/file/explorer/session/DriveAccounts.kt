package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** The Google accounts whose Drives are open here, and which of them is in use. */
internal class DriveAccounts(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository
) {
    fun restore() {
        val accounts = repository.getGoogleAccounts()
        if (accounts.isEmpty()) return
        show(accounts, inUse = repository.currentGoogleAccountEmail ?: accounts.first(), clearsProblem = false)
        measure()
    }

    fun signIn(email: String) {
        repository.setGoogleAccount(email)
        show(repository.getGoogleAccounts(), inUse = email, clearsProblem = true)
        measure()
    }

    /** Signs out of [email], or of whichever is in use. */
    fun signOut(email: String?) {
        if (email == null) repository.setGoogleAccount(null) else repository.removeGoogleAccount(email)
        val left = repository.getGoogleAccounts()
        show(left, inUse = repository.currentGoogleAccountEmail ?: left.firstOrNull(), clearsProblem = true)
        if (left.isNotEmpty()) measure()
    }

    fun use(email: String) {
        repository.setActiveGoogleAccount(email)
        live.update { it.copy(googleDriveAccountEmail = email) }
    }

    /** Files chosen in Google's own picker, which the app may see whatever else it was granted. */
    fun allow(ids: List<String>) {
        repository.currentDrivePickedIds = ids.toSet()
    }

    /** What's at [location], in the account it names or else the one in use. */
    suspend fun list(location: String): List<FileItem> {
        val account = CloudService.Drive.accountIn(location) ?: live.now.googleDriveAccountEmail
        if (account != null) repository.setActiveGoogleAccount(account)
        return repository.getFiles(location)
    }

    /**
     * Downloads the file at [path] as [name]. A Google document has no file of its own, so it comes as a PDF
     * or a picture under a name that says so. Null if nothing arrived.
     */
    suspend fun download(path: String, name: String): File? =
        repository.downloadDriveFile(CloudService.Drive.idIn(path), name, live.now.googleDriveAccountEmail)
            ?.takeIf { it.exists() && it.length() > 0L }

    /** Says why it couldn't, or null once it's gone. */
    suspend fun delete(file: FileItem): String? = repository.deleteDriveFile(
        if (CloudService.Drive.isFolder(file.path)) CloudService.Drive.idIn(file.path) else file.id.toString()
    )

    /** Signing in or out also takes away whatever was said about the last try. */
    private fun show(accounts: List<String>, inUse: String?, clearsProblem: Boolean) = live.update {
        it.copy(
            isGoogleDriveAuthenticated = accounts.isNotEmpty(),
            googleDriveAccountEmail = inUse,
            googleDriveAccounts = accounts.toPersistentList(),
            errorMessage = if (clearsProblem) null else it.errorMessage
        )
    }

    private fun measure() {
        scope.launch {
            val (used, total) = repository.getDriveStorageQuota() ?: return@launch
            live.update { it.copy(driveStorageTotalBytes = total, driveStorageFreeBytes = total - used) }
        }
    }
}
