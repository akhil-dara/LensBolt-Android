package com.lensbolt.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchListTest {
    private val page = JSONObject("""{"offset":0,"total":4,"files":[
        {"name":"pic-0.jpg","size":2202009,"state":"received"},
        {"name":"pic-1.jpg","size":27000,"state":"receiving","progress":0.370},
        {"name":"pic-2.jpg","size":34000,"state":"waiting"},
        {"name":"x.bin","size":9,"state":"failed","reason":"storage full"}]}""")

    @Test fun summaries_in_real_numbers() {
        // the user's 3-file batch: the files' sum, never the folder's own size
        assertEquals("3 files · 2.2 MB · all verified", BatchWords.done(files = 3, received = 3, failed = 0, bytes = 2_306_867))
        assertEquals("1 file · 9 B · verified", BatchWords.done(1, 1, 0, 9))
        assertEquals("2 of 3 files · 1.9 MB · 1 failed", BatchWords.done(3, 2, 1, 1_992_294))
        assertEquals("12 of 40 files · 18.0 MB of 64.0 MB", BatchWords.receiving(12, 40, 0, 18L shl 20, 64L shl 20))
        assertEquals("12 of 40 files · 18.0 MB of 64.0 MB · 2 failed", BatchWords.receiving(12, 40, 2, 18L shl 20, 64L shl 20))
        assertEquals("Waiting for the file list · 64.0 MB", BatchWords.receiving(0, 0, 0, 1L shl 20, 64L shl 20))
    }

    @Test fun state_labels_and_spoken_rows() {
        assertEquals("Waiting", BatchWords.state(FileState.WAITING, 0f, null))
        assertEquals("Receiving 37%", BatchWords.state(FileState.RECEIVING, 0.37f, null))
        assertEquals("Received", BatchWords.state(FileState.RECEIVED, 1f, null))
        assertEquals("Failed: storage full", BatchWords.state(FileState.FAILED, 0f, "storage full"))
        assertEquals("Failed", BatchWords.state(FileState.FAILED, 0f, null))
        assertEquals("photo.jpg, 2.1 MB, received", BatchWords.spoken("photo.jpg", 2_202_009, "Received"))
        assertEquals("Received 26", BatchWords.filter(Filter.RECEIVED, 26))
    }

    @Test fun pages_fill_the_list_and_filters_count() {
        val b = BatchFiles()
        b.apply(page)
        assertEquals(4, b.total)
        assertEquals("pic-1.jpg", b.name(1))
        assertEquals(FileState.RECEIVING, b.state(1))
        assertEquals(0.37f, b.progress(1), 1e-6f)
        assertEquals("storage full", b.reason(3))
        assertEquals(4, b.count(Filter.ALL))
        assertEquals(1, b.count(Filter.RECEIVED))
        assertEquals(2, b.count(Filter.MISSING))            // neither received nor failed
        assertEquals(1, b.count(Filter.FAILED))
        assertEquals(listOf(1, 2), b.indices(Filter.MISSING).toList())
        assertEquals(2_202_009L + 27_000 + 34_000 + 9, b.bytes())
        assertEquals(2_202_009L, b.bytes(Filter.RECEIVED))
        // a later page moves a file on; the counts follow
        b.apply(JSONObject("""{"offset":1,"total":4,"files":[{"name":"pic-1.jpg","size":27000,"state":"received"}]}"""))
        assertEquals(2, b.count(Filter.RECEIVED))
        assertEquals(1, b.count(Filter.MISSING))
        assertTrue(b.version > 0)
    }

    @Test fun ten_thousand_files_page_in_quickly() {
        val b = BatchFiles()
        val t0 = System.nanoTime()
        for (p in 0 until 10) {
            val files = (0 until 1000).joinToString(",") { i -> """{"name":"file-${p * 1000 + i}.txt","size":${100 + i},"state":"${if (i % 3 == 0) "received" else "waiting"}"}""" }
            b.apply(JSONObject("""{"offset":${p * 1000},"total":10000,"files":[$files]}"""))
        }
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(10_000, b.total)
        assertEquals(3340, b.count(Filter.RECEIVED))
        assertTrue("10,000 rows took $ms ms", ms < 1500)
        val t1 = System.nanoTime()
        repeat(100) { b.indices(Filter.MISSING) }
        assertTrue("filters", (System.nanoTime() - t1) / 1e6 < 500)
    }

    @Test fun a_shorter_total_drops_nothing_it_does_not_know() {
        val b = BatchFiles()
        b.apply(JSONObject("""{"offset":0,"total":0,"files":[]}"""))
        assertEquals(0, b.total)
        assertFalse(b.known)
        b.apply(page)
        assertTrue(b.known)
    }

    // a name never trimmed when it fits in two lines; beyond, the middle goes and the extension always stays
    @Test fun names_keep_their_extension() {
        val fits40 = { s: String -> s.length <= 40 }
        val short = "registry-workbook-2026.zip"
        assertEquals(short, NameFit.shorten(short, fits40))
        val long = "registry-workbook-2026-quarterly-export-final-version-signed.zip"
        val s = NameFit.shorten(long, fits40)
        assertTrue(s, s.length <= 40)
        assertTrue(s, s.endsWith(".zip") && s.contains("…") && s.startsWith("registry-"))
        // as long as it can be: one more character would not fit
        assertTrue(s, s.length >= 39)
        // no extension, or one too long to be one: plain middle cut
        val noExt = NameFit.shorten("a".repeat(30) + "b".repeat(30), fits40)
        assertTrue(noExt, noExt.length <= 40 && noExt.startsWith("a") && noExt.endsWith("b"))
        // a very narrow row still keeps the extension
        assertTrue(NameFit.shorten(long) { it.length <= 8 }.endsWith(".zip"))
    }

    @Test fun save_names_take_the_next_free_number() {
        assertEquals("a.jpg", SaveNames.unique("a.jpg", setOf("b.jpg")))
        assertEquals("a (2).jpg", SaveNames.unique("a.jpg", setOf("a.jpg")))
        assertEquals("a (3).jpg", SaveNames.unique("a.jpg", setOf("A.JPG", "a (2).jpg")))     // case-insensitive storage
        assertEquals("notes (2)", SaveNames.unique("notes", setOf("notes")))
        assertEquals(".hidden (2)", SaveNames.unique(".hidden", setOf(".hidden")))
        assertEquals("archive.tar (2).gz", SaveNames.unique("archive.tar.gz", setOf("archive.tar.gz")))
    }

    // the picked folder in words, never a raw volume id: the volume, then its folders joined with " › "
    @Test fun folders_read_as_volume_and_path() {
        val ext = "com.android.externalstorage.documents"
        assertEquals("Internal storage › Download › Transfers", SaveNames.treeLabel("primary:Download/Transfers", ext))
        assertEquals("SD card › LensBolt", SaveNames.treeLabel("1001-8693:LensBolt", ext))
        assertEquals("Internal storage", SaveNames.treeLabel("primary:", ext))           // a volume's root: no path
        assertEquals("SD card", SaveNames.treeLabel("1A2B-3C4D:", ext))
        assertEquals("SD card › My Photos › 2026+", SaveNames.treeLabel("1A2B-3C4D:My%20Photos/2026+", ext))   // %-decoded; '+' kept
        assertEquals("Internal storage › Download › Transfers", SaveNames.treeLabel("primary:/Download//Transfers/", ext))
        // the Downloads provider: its root, a folder by number (named by its display name when known), a raw path
        assertEquals("Downloads", SaveNames.treeLabel("downloads", "com.android.providers.downloads.documents"))
        assertEquals("Downloads", SaveNames.treeLabel("msf:1001869361", "com.android.providers.downloads.documents"))
        assertEquals("Downloads › LensBolt", SaveNames.treeLabel("msf:1001869361", "com.android.providers.downloads.documents", "LensBolt"))
        assertEquals("Internal storage › Download › LensBolt", SaveNames.treeLabel("raw:/storage/emulated/0/Download/LensBolt", "com.android.providers.downloads.documents"))
        assertEquals("Folder", SaveNames.treeLabel(null))
        // Save all's own folder inside the picked one, and the row's one line before and after a save
        assertEquals("SD card › LensBolt › file-0000.bin +3 more", SaveNames.inside(SaveNames.treeLabel("1001-8693:LensBolt", ext), "file-0000.bin +3 more"))
        assertEquals("SD card", SaveNames.inside("SD card", null))
        assertEquals("Saving to SD card › LensBolt", SaveNames.folderLine("SD card › LensBolt", null))
        assertEquals("Saved 4 files to SD card › LensBolt", SaveNames.folderLine("SD card › LensBolt", "Saved 4 files to SD card › LensBolt"))
        assertEquals("You choose the folder the first time", SaveNames.folderLine(null, null))
    }

    // a received file's SHA-256 from its page (core batch_page_json): kept per file, in the snapshot, and gone when
    // the file is no longer received; a row offers it (shown under the row like the browser's, with Copy)
    @Test fun a_received_file_keeps_its_sha256() {
        val h0 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val b = BatchFiles()
        b.apply(JSONObject("""{"offset":0,"total":3,"files":[
            {"name":"a.jpg","size":3,"state":"received","sha256":"$h0"},
            {"name":"b.jpg","size":4,"state":"received"},
            {"name":"c.jpg","size":5,"state":"receiving","progress":0.5,"sha256":"$h0"}]}"""))
        assertEquals(h0, b.sha256(0))
        assertEquals(null, b.sha256(1))
        assertEquals(null, b.sha256(2))      // only a received file has one
        val snap = b.snapshot()
        val v = b.version
        b.apply(JSONObject("""{"offset":1,"total":3,"files":[{"name":"b.jpg","size":4,"state":"received","sha256":"${h0.uppercase()}"}]}"""))
        assertTrue(b.version > v)
        assertEquals(h0, b.sha256(1))
        assertEquals(h0, snap.sha256(0)); assertEquals(null, snap.sha256(1))
        b.apply(JSONObject("""{"offset":0,"total":1,"files":[{"name":"a.jpg","size":3,"state":"waiting"}]}"""))
        assertEquals(null, b.sha256(0))
        b.apply(JSONObject("""{"offset":0,"total":2,"files":[]}"""))
        assertEquals(null, b.sha256(1))      // a file past the old end starts without one
        // the row's menu: the hash to show or hide when there is one, never otherwise
        assertEquals(listOf(RowAction.SAVE, RowAction.OPEN, RowAction.SHARE, RowAction.SHOW_SHA), BatchWords.rowActions(sha = true, shown = false))
        assertEquals(listOf(RowAction.SAVE, RowAction.OPEN, RowAction.SHARE, RowAction.HIDE_SHA, RowAction.COPY_SHA), BatchWords.rowActions(sha = true, shown = true))
        assertEquals(listOf(RowAction.SAVE, RowAction.OPEN, RowAction.SHARE), BatchWords.rowActions(sha = false, shown = true))
    }
}
