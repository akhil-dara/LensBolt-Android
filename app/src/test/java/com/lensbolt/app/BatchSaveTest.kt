package com.lensbolt.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Task 8 fix round 1: the batch list under Save all (I1, I2, I3, I4, I6) as pure rules
class BatchSaveTest {
    private fun pageOf(off: Int, total: Int, n: Int, state: (Int) -> String) =
        JSONObject("""{"offset":$off,"total":$total,"files":[${(off until off + n).joinToString(",") { """{"name":"f$it.bin","size":${it + 1},"state":"${state(it)}"}""" }}]}""")

    // I6: the counts and sizes per filter are kept as pages change, never recounted per screen update
    @Test fun counts_follow_every_page_without_a_recount() {
        val b = BatchFiles()
        val rnd = java.util.Random(7)
        val states = listOf("waiting", "receiving", "received", "failed")
        repeat(40) {
            val off = rnd.nextInt(10) * 100
            b.apply(pageOf(off, 1000, 100) { states[rnd.nextInt(4)] })
            for (f in Filter.entries) {
                var n = 0; var bytes = 0L
                for (i in 0 until b.total) {
                    val st = b.state(i)
                    val m = when (f) {
                        Filter.ALL -> true; Filter.RECEIVED -> st == FileState.RECEIVED; Filter.FAILED -> st == FileState.FAILED
                        Filter.MISSING -> st == FileState.WAITING || st == FileState.RECEIVING
                    }
                    if (m) { n++; bytes += b.size(i) }
                }
                assertEquals("$f count", n, b.count(f)); assertEquals("$f bytes", bytes, b.bytes(f))
                val snap = b.snapshot()
                assertEquals(n, snap.count(f)); assertEquals(bytes, snap.bytes(f))
            }
        }
        // a shorter list drops the files past its end from the counts
        b.apply(pageOf(0, 10, 10) { "received" })
        assertEquals(10, b.count(Filter.ALL)); assertEquals(10, b.count(Filter.RECEIVED)); assertEquals(55L, b.bytes())
        // a longer one counts its new files as waiting until their page comes
        b.apply(pageOf(0, 12, 0) { "received" })
        assertEquals(12, b.count(Filter.ALL)); assertEquals(2, b.count(Filter.MISSING))
    }

    @Test fun counts_cost_nothing_per_call() {
        val b = BatchFiles()
        for (p in 0 until 10) b.apply(pageOf(p * 1000, 10_000, 1000) { if (it % 3 == 0) "received" else "waiting" })
        val t0 = System.nanoTime()
        var s = 0L
        repeat(200_000) { s += b.count(Filter.RECEIVED) + b.count(Filter.MISSING) + b.bytes(Filter.RECEIVED) }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("200,000 count calls on 10,000 files took $ms ms", ms < 200)
        assertTrue(s > 0)
    }

    // I4: a page read never waits for the receiver: busy keeps what was read and says so (the next tick asks again)
    @Test fun a_busy_receiver_keeps_the_pages_read_so_far() {
        val b = BatchFiles()
        var calls = 0
        val out = PageReader.readAll(b, 1000, { off, n -> calls++; if (off == 0) pageOf(0, 2500, n) { "received" }.toString() else null }, { true })
        assertEquals(PageReader.Outcome.BUSY, out)
        assertEquals(2, calls)
        assertEquals(2500, b.total); assertEquals(1000, b.count(Filter.RECEIVED))
        val done = PageReader.readAll(b, 1000, { off, n -> pageOf(off, 2500, minOf(n, 2500 - off)) { "received" }.toString() }, { true })
        assertEquals(PageReader.Outcome.DONE, done)
        assertEquals(2500, b.count(Filter.RECEIVED))
        // no session (or an older batch's read): stop, nothing changed
        assertEquals(PageReader.Outcome.STOPPED, PageReader.readAll(b, 1000, { _, _ -> PageReader.NO_SESSION }, { true }))
        assertEquals(PageReader.Outcome.STOPPED, PageReader.readAll(b, 1000, { _, _ -> error("not read") }, { false }))
        assertEquals(2500, b.count(Filter.RECEIVED))
    }

    // I2: a batch asks before leaving while any received file has no successful save
    @Test fun back_asks_until_every_received_file_is_saved() {
        val l = SaveLedger()
        val received = 3
        assertTrue(backAsks(true, l.allSaved(received), false))
        l.queue(0); l.done(0, null)
        assertEquals(1, l.savedCount)
        assertTrue("one of three saved: still asks", backAsks(true, l.allSaved(received), false))
        l.queue(1); l.done(1, "storage full")
        assertTrue(backAsks(true, l.allSaved(received), false))
        l.queue(1); l.done(1, null); l.queue(2); l.done(2, null)
        assertFalse(backAsks(true, l.allSaved(received), false))
        // a saved file saved again (its row's Save…) counts once; a failed repeat keeps the earlier save
        l.queue(2); assertEquals(0, l.unsaved(received)); l.done(2, "denied")
        assertEquals(3, l.savedCount); assertEquals("saved", l.note(2))
    }

    @Test fun the_ledger_keeps_running_counts_and_notes() {
        val l = SaveLedger()
        assertNull(l.note(5)); assertTrue(l.needsSave(5))
        l.queue(5); l.queue(6); l.queue(7)
        assertEquals("saving…", l.note(5)); assertFalse(l.needsSave(5)); assertEquals(3, l.queuedCount)
        assertEquals(7, l.unsaved(10))                 // ten received, three on their way
        l.done(5, null); l.done(6, "storage full"); l.forget(7)
        assertEquals("saved", l.note(5)); assertEquals("not saved: storage full", l.note(6)); assertNull(l.note(7))
        assertTrue(l.needsSave(6)); assertTrue(l.needsSave(7)); assertFalse(l.needsSave(5))
        assertEquals(0, l.queuedCount); assertEquals(9, l.unsaved(10))
        l.clear(); assertEquals(0, l.savedCount); assertNull(l.note(5))
    }

    // I3: New transfer over files not saved yet asks first (Save all / Discard), and over a run in progress
    @Test fun new_transfer_asks_over_unsaved_files() {
        assertNull(BatchAsk.leaving(received = 37, saved = 37, queued = 0, newTransfer = true))
        val a = BatchAsk.leaving(received = 40, saved = 3, queued = 0, newTransfer = true)!!
        assertEquals("37 files not saved yet", a.title)
        assertEquals("Save all", a.keep); assertFalse(a.saving)
        assertTrue(a.message, a.message.contains("new transfer removes them"))
        val running = BatchAsk.leaving(received = 40, saved = 3, queued = 37, newTransfer = true)!!
        assertEquals("37 files not saved yet", running.title)
        assertEquals("Keep saving", running.keep); assertTrue(running.saving)
        assertEquals("1 file not saved yet", BatchAsk.leaving(1, 0, 0, newTransfer = false)!!.title)
        assertEquals("1,200 files not saved yet", BatchAsk.leaving(1200, 0, 0, newTransfer = false)!!.title)
        assertTrue(BatchAsk.leaving(1, 0, 0, newTransfer = false)!!.message.contains("next time LensBolt starts"))
    }

    // I1: changing the folder while saves run: the old folder's permission waits for its saves; a failure through
    // the old folder never forgets the new one
    @Test fun a_folder_change_mid_run_keeps_both_folders_right() {
        val k = TreeKeeper()
        repeat(3) { k.queued("A") }
        assertEquals(emptyList<String>(), k.picked(old = "A", picked = "B"))   // A still has saves queued
        assertTrue(k.inUse("A"))
        assertEquals(emptyList<String>(), k.ended("A", current = "B"))
        assertEquals(emptyList<String>(), k.ended("A", current = "B"))
        assertEquals(listOf("A"), k.ended("A", current = "B"))                  // the last one: A goes back
        assertEquals(emptyList<String>(), k.ended("A", current = "B"))          // once
        assertFalse(k.inUse("A"))
        // nothing queued: the old folder goes back at once
        assertEquals(listOf("B"), k.picked(old = "B", picked = "C"))
        assertEquals(emptyList<String>(), k.picked(old = "C", picked = "C"))
        // picked back before its saves ended: kept
        k.queued("C")
        assertEquals(emptyList<String>(), k.picked("C", "D"))
        assertEquals(listOf("D"), k.picked("D", "C"))
        assertEquals(emptyList<String>(), k.ended("C", current = "C"))
    }

    // fix round 2, minor 3: a folder lost mid-run is one loss for the whole run; an earlier folder's failure is that
    // file's alone, and the remembered folder is forgotten only when it is the one that failed
    @Test fun a_lost_folder_is_one_loss_for_the_whole_run() {
        val k = TreeKeeper()
        assertEquals(TreeKeeper.Lost.FORGET, k.lost(failed = "A", current = "A"))
        // the remembered folder is gone now: the run's other files through A are the same loss (not tried)
        assertEquals(TreeKeeper.Lost.SAME, k.lost(failed = "A", current = null))
        assertEquals(TreeKeeper.Lost.SAME, k.lost(failed = "A", current = null))
        // a new folder picked: a later failure through it is a new loss; one through a folder changed away from is
        // that file's failure
        k.picked(old = null, picked = "B")
        assertEquals(TreeKeeper.Lost.OTHER, k.lost(failed = "C", current = "B"))
        assertEquals(TreeKeeper.Lost.FORGET, k.lost(failed = "B", current = "B"))
        assertEquals(TreeKeeper.Lost.OTHER, k.lost(failed = "C", current = null))
    }

    // fix round 2, I2: Back on a batch asks whenever a received file has no save, saves running or not; a single
    // file's copy in flight still just leaves (it finishes on its own)
    @Test fun back_on_a_batch_asks_even_while_saves_run() {
        assertTrue(backEnabled(paneOpen = false, batch = true, done = true, kept = false, asked = false, saving = false))
        assertTrue(backEnabled(paneOpen = false, batch = true, done = true, kept = false, asked = false, saving = true))
        assertFalse(backEnabled(paneOpen = false, batch = true, done = true, kept = true, asked = false, saving = false))
        assertFalse(backEnabled(paneOpen = false, batch = true, done = true, kept = false, asked = true, saving = false))
        assertFalse(backEnabled(paneOpen = false, batch = false, done = true, kept = false, asked = false, saving = true))
        assertTrue(backEnabled(paneOpen = false, batch = false, done = true, kept = false, asked = false, saving = false))
        assertFalse(backEnabled(paneOpen = false, batch = false, done = false, kept = false, asked = false, saving = false))
        assertTrue(backEnabled(paneOpen = true, batch = false, done = false, kept = true, asked = true, saving = true))
    }

    // fix round 2, I2: what leaving asks: Save all while any unsaved file is not on its way, Keep saving once all are
    @Test fun leaving_offers_save_all_or_keep_saving() {
        val l = SaveLedger()
        fun ask() = BatchAsk.leaving(40, l.savedCount, l.queuedUnsavedCount, newTransfer = false)!!
        // nothing saved
        ask().let { assertEquals("40 files not saved yet", it.title); assertEquals("Save all", it.keep); assertFalse(it.saving) }
        // one row's Save… in flight: the other 39 are not on their way, so Save all (it saves them)
        l.queue(0)
        ask().let { assertEquals("40 files not saved yet", it.title); assertEquals("Save all", it.keep); assertFalse(it.saving)
            assertTrue(it.message, it.message.contains("1 is being saved")) }
        // a Save all run: every unsaved file is on its way
        for (i in 1 until 40) l.queue(i)
        ask().let { assertEquals("Keep saving", it.keep); assertTrue(it.saving) }
        for (i in 0 until 10) l.done(i, null)
        ask().let { assertEquals("30 files not saved yet", it.title); assertEquals("Keep saving", it.keep) }
        assertEquals(30, l.queuedUnsavedCount)
    }

    // I6: at most one screen update per interval, however many saves report
    @Test fun screen_updates_are_coalesced() {
        val c = Coalesce(200)
        assertEquals(0L, c.request(1000))                          // the first runs at once
        assertNull(c.request(1001)); assertNull(c.request(1100))  // already due: nothing more scheduled
        c.ran(1000)
        assertEquals(150L, c.request(1050))                        // 200 ms after the last update
        assertNull(c.request(1060))
        c.ran(1200)
        assertEquals(0L, c.request(1500))
        c.ran(1500)
        var runs = 0; var now = 10_000L; var due = -1L
        repeat(10_000) { now += 1; c.request(now)?.let { if (due < 0) due = now + it }; if (due in 0..now) { c.ran(now); runs++; due = -1 } }
        assertTrue("$runs updates in 10 s", runs in 45..55)
    }

    // I6: a folder's names stay unique in time linear in the files saved (10,000 files into one folder)
    @Test fun ten_thousand_names_in_one_folder() {
        val t = SaveNames.Taken()
        val t0 = System.nanoTime()
        val out = (0 until 10_000).map { t.take(if (it % 2 == 0) "IMG_${it / 2}.jpg" else "img_${it / 2}.JPG") }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("10,000 names took $ms ms", ms < 500)
        assertEquals("IMG_0.jpg", out[0]); assertEquals("img_0 (2).JPG", out[1])
        assertEquals(10_000, out.map { it.lowercase() }.toSet().size)
        assertEquals("a (3).jpg", SaveNames.Taken(listOf("A.JPG", "a (2).jpg")).take("a.jpg"))
    }
}
