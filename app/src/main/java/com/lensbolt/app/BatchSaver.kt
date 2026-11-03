package com.lensbolt.app

// Save all: verified files of a batch copied from the app's private storage into a folder the user picked
// (ACTION_OPEN_DOCUMENT_TREE, permission kept with takePersistableUriPermission). Each batch gets its own
// folder inside the picked one ("<picked>/<batch name>", " (2)" when taken); a name already in it gets " (2)";
// a file that fails is named with its reason and never stops the others. Runs on one background thread.

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

class BatchSaver(private val ctx: Context) {
    /** One file's outcome: `error` null when saved (as `savedAs`, document `uri`). */
    data class Result(val error: String?, val savedAs: String, val uri: Uri?)

    /** The picked folder is gone or no longer writable: the whole run stops and the user picks again. */
    class FolderLost(msg: String) : Exception(msg)

    private var tree: Uri? = null
    private var batchKey: String? = null
    private var folder: DocumentFile? = null
    private var rootName: String? = null
    private val taken = SaveNames.Taken()

    /** The folder this batch is saved in, made on first use (per tree and batch session). */
    private fun folderFor(tree: Uri, batch: String, key: String): DocumentFile {
        folder?.let { if (this.tree == tree && batchKey == key && it.exists()) return it }
        val root = DocumentFile.fromTreeUri(ctx, tree)
        if (root == null || !root.exists() || !root.canWrite()) throw FolderLost("cannot write")
        val names = root.listFiles().mapNotNullTo(HashSet()) { it.name }
        val made = root.createDirectory(SaveNames.unique(batch, names)) ?: throw FolderLost("cannot make a folder")
        this.tree = tree; batchKey = key; folder = made; rootName = root.name
        taken.clear()
        return made
    }

    /** Where this batch's files go, in words ("Internal storage › Download › trip"), once a file was saved. */
    fun where(tree: Uri): String? {
        val f = folder ?: return null
        if (this.tree != tree) return null
        return SaveNames.inside(label(tree, rootName), f.name)
    }

    /** Copies `src` into the batch's folder under `name` (or the next free " (n)"). `key` names the batch
     *  session (a new transfer of the same name gets a new folder). */
    fun save(tree: Uri, batch: String, key: String, src: File, name: String, onPct: (Int) -> Unit): Result {
        val dir = folderFor(tree, batch, key)
        val unique = taken.take(name)
        val doc = try { dir.createFile(mimeOf(unique), unique) } catch (e: SecurityException) { throw FolderLost(e.message ?: "denied") }
            ?: return Result("could not be made in the folder", unique, null)
        val err = copy(src, doc.uri, onPct)
        if (err != null) { try { doc.delete() } catch (_: Throwable) {}; return Result(err, unique, null) }
        return Result(null, doc.name ?: unique, doc.uri)
    }

    /** Copies `src` into a document the user chose (the system save screen); the reason when it fails. */
    fun copy(src: File, uri: Uri, onPct: (Int) -> Unit): String? {
        if (!src.isFile) return "the received file was removed"
        return try {
            val out = (try { ctx.contentResolver.openOutputStream(uri, "wt") } catch (_: Throwable) { ctx.contentResolver.openOutputStream(uri, "w") })
                ?: return "the folder cannot be written"
            val total = src.length().coerceAtLeast(1)
            out.use { o ->
                src.inputStream().use { i ->
                    val buf = buffer(); var done = 0L; var shown = -1
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; onPct(pct) }
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            throw FolderLost(e.message ?: "denied")
        } catch (e: IOException) {
            words(e)
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
    }

    companion object {
        private val buffers = ThreadLocal.withInitial { ByteArray(1 shl 20) }

        /** This thread's 1 MB copy buffer (one per saving thread, reused for every file). */
        fun buffer(): ByteArray = buffers.get()!!

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

        /** A storage failure in plain words. */
        fun words(e: IOException): String {
            val m = (e.message ?: "").lowercase()
            return when {
                "enospc" in m || "no space" in m || "full" in m -> "storage full"
                "erofs" in m || "read-only" in m -> "storage not writable"
                "eacces" in m || "permission" in m -> "not allowed to write there"
                else -> e.message ?: "write error"
            }
        }

        /** A picked folder in words; `name`, its display name, names a Downloads folder the id does not. */
        fun label(tree: Uri, name: String? = null): String =
            SaveNames.treeLabel(try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Throwable) { null }, tree.authority, name)

        /** Where a document chosen on the system save screen is, in words: its folder on the phone's storage
         *  when the document says so, else "the chosen place". */
        fun whereSaved(uri: Uri): String {
            return try {
                when (uri.authority) {
                    "com.android.externalstorage.documents" -> {
                        val id = DocumentsContract.getDocumentId(uri)
                        SaveNames.treeLabel(id.substringBeforeLast('/', id.substringBefore(':') + ":"))
                    }
                    "com.android.providers.downloads.documents" -> "Downloads"
                    else -> "the chosen place"
                }
            } catch (_: Throwable) { "the chosen place" }
        }
    }
}
