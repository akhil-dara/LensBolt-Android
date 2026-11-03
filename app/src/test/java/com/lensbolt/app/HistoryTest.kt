package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HistoryTest {
    private fun dir(): File = Files.createTempDirectory("lb-history").toFile()

    private val a = HistoryItem("photo.jpg", 2_202_009, 1_793_000_000_000, "Download/Transfers/trip", "content://x/1", "trip")
    private val b = HistoryItem("notes \"v2\".txt", 9, 1_793_000_100_000, "Documents", null, null,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun survives_a_restart() {
        val d = dir()
        History(File(d, "history.json")).apply { add(a); add(b) }
        val again = History(File(d, "history.json"))
        // newest first, every field kept (names with quotes too)
        assertEquals(listOf(b, a), again.items())
    }

    @Test fun clear_empties_it_for_good() {
        val d = dir()
        val h = History(File(d, "history.json")).apply { add(a) }
        h.clear()
        assertEquals(emptyList<HistoryItem>(), h.items())
        assertEquals(emptyList<HistoryItem>(), History(File(d, "history.json")).items())
    }

    @Test fun keeps_the_newest_few_hundred() {
        val d = dir()
        val h = History(File(d, "history.json"))
        repeat(History.MAX + 20) { h.add(a.copy(name = "f$it.bin", date = it.toLong())) }
        val items = History(File(d, "history.json")).items()
        assertEquals(History.MAX, items.size)
        assertEquals("f${History.MAX + 19}.bin", items.first().name)
    }

    @Test fun a_damaged_file_reads_as_what_it_can() {
        val d = dir()
        val f = File(d, "history.json")
        f.writeText("not json at all")
        assertEquals(emptyList<HistoryItem>(), History(f).items())
        f.writeText("""[{"name":"ok.txt","size":3,"date":5,"savedTo":"Documents"},{"size":"x"},7]""")
        val items = History(f).items()
        assertEquals(1, items.size)
        assertEquals("ok.txt", items[0].name)
        assertTrue(items[0].uri == null && items[0].batch == null)
    }

    // I5: Save all writes one entry per batch and folder (name, file count, total size, folder), once per run
    @Test fun a_batch_is_one_entry_written_once_per_run() {
        val d = dir()
        val h = History(File(d, "history.json"))
        repeat(30) { h.add(a.copy(name = "earlier-$it.jpg", date = it.toLong())) }
        val before = h.writes
        val b = HistoryBatcher(h)
        repeat(10_000) { b.saved("s3/1|tree-A", "trip", "Download/Transfers/trip", 1_000L + it, 1_793_000_000_000L + it) }
        assertEquals("nothing written while the run goes on", before, h.writes)
        b.flush()
        assertEquals("one write for the run", before + 1, h.writes)
        b.flush()
        assertEquals("nothing new: no write", before + 1, h.writes)
        val items = History(File(d, "history.json")).items()
        assertEquals(31, items.size)                           // the earlier history is all still there
        val e = items.first()
        assertEquals("trip", e.name); assertEquals(10_000, e.files)
        assertEquals((0 until 10_000).sumOf { 1_000L + it }, e.size)
        assertEquals("Download/Transfers/trip", e.savedTo)
        assertEquals("earlier-29.jpg", items[1].name)
        // a second run in the same batch and folder adds to the same entry
        b.saved("s3/1|tree-A", "trip", "Download/Transfers/trip", 5, 1_793_000_100_000L); b.flush()
        val again = History(File(d, "history.json")).items()
        assertEquals(31, again.size); assertEquals(10_001, again.first().files)
        // another folder (changed mid-batch) is its own entry
        b.saved("s3/1|tree-B", "trip", "Documents/trip", 5, 1_793_000_200_000L); b.flush()
        assertEquals(32, History(File(d, "history.json")).items().size)
    }

    // fix round 2, I1: every app start numbers its sessions from s1 again; a batch's entry is still its own
    @Test fun batches_of_different_runs_never_share_an_entry() {
        val d = dir()
        HistoryBatcher(History(File(d, "history.json"))).apply {
            saved(HistoryBatcher.batchId("s1", 1, HistoryBatcher.token(), "tree-A"), "trip", "Download/trip", 10, 1); flush()
        }
        // the next day, a new process: the same session folder and number, the same batch name and folder
        HistoryBatcher(History(File(d, "history.json"))).apply {
            saved(HistoryBatcher.batchId("s1", 1, HistoryBatcher.token(), "tree-A"), "trip", "Download/trip (2)", 20, 2); flush()
        }
        val items = History(File(d, "history.json")).items()
        assertEquals(listOf(20L, 10L), items.map { it.size })
        assertEquals(listOf("Download/trip (2)", "Download/trip"), items.map { it.savedTo })
    }

    // fix round 2, minor 5: a long run also lands every 500 files or 5 s (one write each), and at its end
    @Test fun a_long_run_lands_in_steps() {
        val h = History(File(dir(), "history.json"))
        val b = HistoryBatcher(h)
        var now = 0L
        repeat(1_200) { now += 1; b.saved("id", "trip", "Download/trip", 1, now); if (b.due(now)) b.flush() }
        assertEquals(2, h.writes); assertEquals(1000, h.items().first().files)
        b.flush()
        assertEquals(3, h.writes); assertEquals(1200, h.items().first().files)
        assertFalse(b.due(now))
        // a slow run: written 5 s after the first file not yet written
        b.saved("id", "trip", "Download/trip", 1, 100_000); assertFalse(b.due(100_000))
        b.saved("id", "trip", "Download/trip", 1, 104_999); assertFalse(b.due(104_999))
        b.saved("id", "trip", "Download/trip", 1, 105_000); assertTrue(b.due(105_000))
    }

    @Test fun batch_entries_read_as_batches() {
        val e = HistoryItem("trip", 67_108_864, 0, "Download/Transfers/trip", null, null, files = 37)
        assertEquals("37 files · 64.0 MB · today", HistoryWords.meta(e, "today"))
        assertEquals("2.1 MB · today", HistoryWords.meta(a, "today"))
        assertEquals("Saved to Download/Transfers/trip", HistoryWords.where(e))
        assertEquals("Saved to Download/Transfers/trip · from trip", HistoryWords.where(a))
        assertEquals("trip, 37 files, 64.0 MB, saved today to Download/Transfers/trip", HistoryWords.spoken(e, "today"))
    }

    // a row's SHA-256 is read by TalkBack (the row's description replaces its text), in fours, and can be copied
    @Test fun the_sha_is_spoken_and_copyable() {
        val sha = b.sha!!
        assertEquals("notes \"v2\".txt, 9 B, saved today to Documents. SHA-256: b a 7 8, 1 6 b f, 8 f 0 1",
            HistoryWords.spoken(b, "today").substringBefore(", c f e a"))
        assertTrue(HistoryWords.spoken(b, "today").endsWith(ShaText.spoken(sha)))
        assertEquals(listOf(RowAction.SHOW_SHA, RowAction.COPY_SHA), HistoryWords.actions(b))   // no saved document: its hash only
        assertEquals(listOf(RowAction.OPEN, RowAction.SHARE), HistoryWords.actions(a))     // no hash known
        assertEquals(listOf(RowAction.OPEN, RowAction.SHARE, RowAction.SHOW_SHA, RowAction.COPY_SHA), HistoryWords.actions(a.copy(sha = sha)))
        assertEquals("Copy SHA-256", RowAction.COPY_SHA.label)
    }

    // M3: the permissions kept for single saves go with the items that leave (cleared or past the cap), never the
    // Save all folder's, never one an item still listed uses
    @Test fun permissions_go_with_the_items_that_leave() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val single = a.copy(uri = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fphoto.jpg")
        val again = single.copy(date = 9)
        val inTree = a.copy(uri = tree)
        assertEquals(listOf(single.uri), History.releasable(listOf(single, inTree, b), emptyList(), tree))
        assertEquals(emptyList<String>(), History.releasable(listOf(single), listOf(again), tree))
        // past the cap: the evicted items come back to the caller
        val d = dir()
        val h = History(File(d, "history.json"))
        repeat(History.MAX) { h.add(a.copy(name = "f$it", uri = "content://x/$it")) }
        val gone = h.add(a.copy(name = "new", uri = "content://x/new"))
        assertEquals(listOf("content://x/0"), gone.map { it.uri })
    }

    @Test fun no_file_is_ever_copied() {
        // metadata only: the history file is all there is in its folder
        val d = dir()
        History(File(d, "history.json")).apply { add(a); add(b) }
        assertEquals(listOf("history.json"), d.list()!!.toList())
        assertTrue(File(d, "history.json").length() < 2000)
    }
}
