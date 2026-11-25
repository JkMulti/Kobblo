package com.kobblo.gallery.file.explorer.tools.picker

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.files.MimeGuess
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows Kobblo's storage in the system's own picker, and gives the app's picker real document
 * URIs to hand back, which other apps can keep access to across restarts.
 *
 * It exposes what the system's own storage root does, minus the app's trash. The vault lives in the
 * app's private directory, so it can't be reached from here.
 */
class StorageDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        context?.let(StorageVolumes::start)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = appContext()
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!hasAllFilesAccess()) return cursor
        StorageVolumes.refresh(ctx)
        StorageVolumes.snapshot.forEach { volume ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, volume.id)
                add(Root.COLUMN_DOCUMENT_ID, DocumentIds.rootDocumentId(volume.id))
                add(Root.COLUMN_TITLE, ctx.getString(R.string.tools_picker_root_title))
                add(Root.COLUMN_SUMMARY, volume.name)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or
                        if (volume.isReadOnly) 0 else Root.FLAG_SUPPORTS_CREATE
                )
                add(Root.COLUMN_AVAILABLE_BYTES, volume.freeBytes)
                add(Root.COLUMN_CAPACITY_BYTES, volume.totalBytes)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(cursor, documentId, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = fileFor(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(appContext())
        parent.listFiles()
            ?.filter { !it.name.startsWith(".") && !locks.covers(it.path) }
            ?.forEach { addRow(cursor, DocumentIds.child(parentDocumentId, it.name), it) }
        cursor.setNotificationUri(
            appContext().contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId)
        )
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (file.isDirectory) throw FileNotFoundException("Can't open a folder as a file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getDocumentType(documentId: String): String {
        val file = fileFor(documentId)
        return if (file.isDirectory) Document.MIME_TYPE_DIR else mimeOf(file.name)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        DocumentIds.isChild(parentDocumentId, documentId)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        val safeName = displayName.replace('/', '_')
        var target = File(parent, safeName)
        var counter = 1
        while (target.exists()) {
            val stem = safeName.substringBeforeLast('.', safeName)
            val ext = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            target = File(parent, "$stem ($counter)$ext")
            counter++
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Couldn't create $safeName")
        notifyChildrenChanged(parentDocumentId)
        return DocumentIds.child(parentDocumentId, target.name)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val parentId = DocumentIds.parent(documentId)
            ?: throw FileNotFoundException("Can't rename the top of a volume")
        val target = File(file.parentFile, displayName.replace('/', '_'))
        if (target.exists() || !file.renameTo(target)) throw FileNotFoundException("Couldn't rename ${file.name}")
        // Something locked may be inside it.
        com.kobblo.gallery.file.explorer.fileops.Locks.moved(appContext(), file.path, target.path)
        notifyChildrenChanged(parentId)
        return DocumentIds.child(parentId, target.name)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (DocumentIds.parent(documentId) == null) throw FileNotFoundException("Can't delete the top of a volume")
        if (!com.kobblo.gallery.file.explorer.fileops.SafeFiles.deleteTree(file)) throw FileNotFoundException("Couldn't delete ${file.name}")
        com.kobblo.gallery.file.explorer.fileops.Locks.forget(appContext(), file.path)
        DocumentIds.parent(documentId)?.let(::notifyChildrenChanged)
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        appContext().contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId),
            null
        )
    }

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        val isDir = file.isDirectory
        val writable = file.canWrite()
        var flags = 0
        if (writable) {
            flags = flags or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
            flags = flags or if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        }
        val mime = if (isDir) Document.MIME_TYPE_DIR else mimeOf(file.name)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { StorageVolumes.forPath(file.path)?.name ?: file.path })
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point,
        signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {
        val pfd = ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
        return android.content.res.AssetFileDescriptor(pfd, 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun fileFor(documentId: String): File {
        if (!hasAllFilesAccess()) throw FileNotFoundException("Files access isn't granted")
        StorageVolumes.start(appContext())
        val file = DocumentIds.resolve(documentId, StorageVolumes.snapshot.associate { it.id to it.path })
            ?: throw FileNotFoundException("No such document")
        // Everything that reaches a document comes through here, so a lock holds even for an id that was saved
        // before it was locked. This keeps it out of what Kobblo hands over: other apps can still reach the
        // file by their own means, which is what makes this a lock and not a vault.
        if (com.kobblo.gallery.file.explorer.fileops.Locks.covers(appContext(), file.path)) throw FileNotFoundException("No such document")
        return file
    }

    private fun authority() = authorityFor(appContext())

    /** Not ContentProvider's requireContext(), which only exists from Android 11. Other apps reach this code directly. */
    private fun appContext(): Context = checkNotNull(context) { "Used before onCreate" }

    private fun hasAllFilesAccess(): Boolean = com.kobblo.gallery.file.explorer.compat.StorageAccess.has(appContext())

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_CAPACITY_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )

        fun authorityFor(context: Context) = "${context.packageName}.documents"

        fun mimeOf(name: String): String =
            MimeGuess.forName(name) { ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }

        /** The document URI for a file on any mounted volume, or null if it isn't on one. */
        fun uriFor(context: Context, path: String): Uri? {
            StorageVolumes.start(context)
            val volume = StorageVolumes.forPath(path) ?: return null
            val relative = path.removePrefix(volume.path).trimStart('/')
            return DocumentsContract.buildDocumentUri(authorityFor(context), "${volume.id}:$relative")
        }
    }
}

/**
 * A document's id is its volume's id and its path inside that volume, as `primary:Download/a.txt`.
 * Pure, so the rules that keep ids inside their volume can be tested off the device.
 */
internal object DocumentIds {
    /** The app's trash holds files someone already deleted, so it never leaves the app. */
    private const val TRASH_DIR = ".kobblo_trash"

    fun rootDocumentId(volumeId: String) = "$volumeId:"

    fun child(parentId: String, name: String): String {
        val relative = parentId.substringAfter(':')
        return parentId.substringBefore(':') + ":" + if (relative.isEmpty()) name else "$relative/$name"
    }

    /** The parent's id, or null when [id] is the top of its volume. */
    fun parent(id: String): String? {
        val relative = id.substringAfter(':', "")
        if (relative.isEmpty()) return null
        return id.substringBefore(':') + ":" + relative.substringBeforeLast('/', "")
    }

    fun isChild(parentId: String, id: String): Boolean {
        if (parentId.substringBefore(':') != id.substringBefore(':')) return false
        if (hasUnsafeSegment(id)) return false
        val parent = parentId.substringAfter(':')
        val child = id.substringAfter(':')
        return if (parent.isEmpty()) child.isNotEmpty() else child.startsWith("$parent/")
    }

    /** The file for [id], or null when its volume isn't mounted or the path would leave the volume. */
    fun resolve(id: String, volumePaths: Map<String, String>): File? {
        val root = volumePaths[id.substringBefore(':')] ?: return null
        if (hasUnsafeSegment(id)) return null
        val relative = id.substringAfter(':', "")
        return if (relative.isEmpty()) File(root) else File(root, relative)
    }

    private fun hasUnsafeSegment(id: String): Boolean =
        id.substringAfter(':', "").split('/').any { it == ".." || it == "." || it == TRASH_DIR }
}
