package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The session teardown rules (Sessions.kt): a new session starts at once in its own directory, the old one is
// destroyed in the background and only then its directory removed; nothing waits on the caller (the UI thread) or
// on the lock the camera thread takes.
class SessionsTest {
    private class Rig(val gate: CountDownLatch = CountDownLatch(0), val lateWrite: Boolean = false) {
        val root: File = Files.createTempDirectory("lb-sessions").toFile()
        val bg = Executors.newSingleThreadExecutor()
        val made = mutableMapOf<Long, File>()
        val destroyed = mutableListOf<Long>()
        val destroyThreads = mutableListOf<Thread>()
        var next = 0L
        val s = Sessions(root,
            create = { d -> next++; synchronized(made) { made[next] = d }; next },
            destroy = { h ->
                gate.await()
                // the collector finishing a large file as it is joined: its temporary file lands in its own dir
                if (lateWrite) File(synchronized(made) { made[h]!! }, "big.bin.lbtmp").writeText("late")
                synchronized(destroyed) { destroyed += h; destroyThreads += Thread.currentThread() }
            },
            background = { bg.execute(it) })
        fun drain() { bg.shutdown(); assertTrue(bg.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun eachSessionHasItsOwnDirectoryUnderTheRoot() {
        val r = Rig()
        r.s.start(); val a = r.s.dir!!
        r.s.start(); val b = r.s.dir!!
        assertNotEquals(a, b)
        assertEquals(r.root, a.parentFile); assertEquals(r.root, b.parentFile)
        assertTrue(b.isDirectory)
        r.drain()
    }

    @Test fun aNewSessionStartsWhileTheOldOneIsStillTornDown() {
        val gate = CountDownLatch(1)
        val r = Rig(gate)
        r.s.start(); val old = r.s.dir!!
        val t0 = System.nanoTime()
        r.s.start()
        assertTrue("start waited on the old session's teardown", System.nanoTime() - t0 < 500_000_000L)
        assertEquals(2L, r.s.withHandle { it })
        // the camera thread is never held up by the teardown (it reads the handle under the lock)
        val cam = Executors.newSingleThreadExecutor()
        val h = cam.submit<Long> { r.s.withHandle { it } }.get(1, TimeUnit.SECONDS)
        assertEquals(2L, h)
        cam.shutdown()
        assertTrue("the old directory goes only after its session is destroyed", old.isDirectory)
        gate.countDown(); r.drain()
        assertEquals(listOf(1L), r.destroyed)
        assertFalse(old.exists())
        assertTrue(r.s.dir!!.isDirectory)
        assertNotEquals(Thread.currentThread(), r.destroyThreads.single())
    }

    @Test fun aLateWriteByTheOldSessionLeavesNothingBehind() {
        val r = Rig(lateWrite = true)
        r.s.start(); r.s.start()
        r.drain()
        assertEquals(listOf(r.s.dir!!.name), r.root.list()!!.toList())
        assertTrue(r.s.dir!!.list()!!.isEmpty())
    }

    @Test fun stopEndsTheSessionAtOnceAndRemovesItsDirectoryAfterward() {
        val gate = CountDownLatch(1)
        val r = Rig(gate)
        r.s.start(); val d = r.s.dir!!
        r.s.stop()
        assertEquals(0L, r.s.withHandle { it })
        assertNull(r.s.dir)
        assertTrue(d.isDirectory)
        gate.countDown(); r.drain()
        assertEquals(listOf(1L), r.destroyed)
        assertFalse(d.exists())
    }

    @Test fun releaseTearsDownInTheBackgroundAndKeepsTheReceivedFile() {
        val gate = CountDownLatch(1)
        val r = Rig(gate)
        r.s.start(); val d = r.s.dir!!
        File(d, "photo.jpg").writeText("received")
        r.s.release()
        assertEquals(0L, r.s.withHandle { it })
        gate.countDown(); r.drain()
        assertEquals(listOf(1L), r.destroyed)
        assertTrue("an unsaved file stays until the next app start", File(d, "photo.jpg").isFile)
    }

    @Test fun anAdoptedDirectoryGoesWithTheNextStart() {
        val r = Rig()
        val restored = File(r.root, "s7").apply { mkdirs() }
        File(restored, "doc.pdf").writeText("x")
        r.s.adopt(restored)
        r.s.start()
        r.drain()
        assertFalse(restored.exists())
        assertEquals("s8", r.s.dir!!.name)
        assertTrue(r.destroyed.isEmpty())
    }

    @Test fun nextDirIsNeverOneThatIsStillThere() {
        val root = Files.createTempDirectory("lb-next").toFile()
        assertEquals("s1", Sessions.nextDir(root, 0).name)
        File(root, "s3").mkdirs(); File(root, "other").mkdirs()
        assertEquals("s4", Sessions.nextDir(root, 0).name)
        assertEquals("s10", Sessions.nextDir(root, 9).name)
        Sessions.wipe(root)
        assertTrue(root.isDirectory && root.list()!!.isEmpty())
    }
}
