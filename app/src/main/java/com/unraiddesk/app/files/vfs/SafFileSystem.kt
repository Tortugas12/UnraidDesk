package com.unraiddesk.app.files.vfs

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Android Storage Access Framework (SAF) provider for persistent ACTION_OPEN_DOCUMENT_TREE grants
 * (Google Drive, OneDrive, Dropbox, USB OTG).
 */
class SafFileSystem(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
    override val rootId: String = treeUri.toString(),
    override val displayName: String = "Cloud / SAF Storage"
) : FileSystemProvider {

    override val protocol: StorageProtocol = StorageProtocol.SAF

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val dirDocId = resolveDocumentId(normalized)
            ?: throw FileNotFoundException("Directory not found: $path")

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val entries = mutableListOf<FileEntry>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                val modified = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx)
                val childPath = if (normalized.isEmpty()) name else "$normalized/$name"
                entries.add(
                    FileEntry(
                        name = name,
                        path = childPath,
                        isDirectory = isDir,
                        sizeBytes = size,
                        modifiedMillis = modified,
                        mimeType = mime,
                        protocol = StorageProtocol.SAF
                    )
                )
            }
        }
        entries
    }

    override suspend fun createDirectory(parentPath: String, name: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(name)
        val normalizedParent = PathGuard.normalizeRelativePath(parentPath)
        val parentDocId = resolveDocumentId(normalizedParent)
            ?: throw FileNotFoundException("Parent directory not found: $parentPath")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val createdUri = DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            cleanName
        ) ?: throw IOException("Failed to create SAF directory '$cleanName'.")

        val childPath = if (normalizedParent.isEmpty()) cleanName else "$normalizedParent/$cleanName"
        FileEntry(
            name = cleanName,
            path = childPath,
            isDirectory = true,
            sizeBytes = 0L,
            modifiedMillis = System.currentTimeMillis(),
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            protocol = StorageProtocol.SAF
        ).also { _ -> createdUri.toString() }
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(newName)
        val normalized = PathGuard.normalizeRelativePath(path)
        val docId = resolveDocumentId(normalized)
            ?: throw FileNotFoundException("Item not found: $path")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        DocumentsContract.renameDocument(contentResolver, docUri, cleanName)
            ?: throw IOException("Failed to rename '$path' to '$cleanName'.")

        val parentRel = normalized.substringBeforeLast('/', "")
        val newRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        FileEntry(
            name = cleanName,
            path = newRel,
            isDirectory = false,
            protocol = StorageProtocol.SAF
        )
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val docId = resolveDocumentId(normalized) ?: return@withContext
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        if (!DocumentsContract.deleteDocument(contentResolver, docUri)) {
            throw IOException("Failed to delete SAF document: $path")
        }
    }

    @android.annotation.SuppressLint("Recycle")
    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val docId = resolveDocumentId(normalized)
            ?: throw FileNotFoundException("File not found: $path")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        contentResolver.openInputStream(docUri)
            ?: throw IOException("Unable to open input stream for $path")
    }

    @android.annotation.SuppressLint("Recycle")
    override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val existingDocId = resolveDocumentId(normalized)
        val targetUri = if (existingDocId != null) {
            if (!overwrite) {
                throw IOException("Destination already exists: $path")
            }
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
        } else {
            val parentRel = normalized.substringBeforeLast('/', "")
            val fileName = normalized.substringAfterLast('/')
            val parentDocId = resolveDocumentId(parentRel)
                ?: throw FileNotFoundException("Parent directory not found: $parentRel")
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                "application/octet-stream",
                fileName
            ) ?: throw IOException("Failed to create SAF file: $path")
        }
        val mode = if (overwrite) "wt" else "w"
        contentResolver.openOutputStream(targetUri, mode)
            ?: throw IOException("Unable to open output stream for $path")
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolveDocumentId(PathGuard.normalizeRelativePath(path)) != null
    }

    private fun resolveDocumentId(normalizedPath: String): String? {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        if (normalizedPath.isEmpty()) return currentDocId

        for (segment in normalizedPath.split('/')) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            var foundId: String? = null
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == segment) {
                        foundId = cursor.getString(idIdx)
                        break
                    }
                }
            }
            currentDocId = foundId ?: return null
        }
        return currentDocId
    }
}
