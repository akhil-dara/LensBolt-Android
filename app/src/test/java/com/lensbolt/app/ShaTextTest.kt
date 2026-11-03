package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaTextTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun theHashOfABytesStream() {
        assertEquals(abc, ShaText.of("abc".byteInputStream()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ShaText.of(ByteArray(0).inputStream()))
    }

    // four-character groups, eight to a line (two lines for 64 digits)
    @Test fun groupsOfFourEightToALine() {
        assertEquals("ba78 16bf 8f01 cfea 4141 40de 5dae 2223\nb003 61a3 9617 7a9c b410 ff61 f200 15ad", ShaText.groups(abc))
    }

    // TalkBack reads it group by group, each character on its own
    @Test fun spokenGroupByGroup() {
        val s = ShaText.spoken(abc)
        assertTrue(s.startsWith("SHA-256: b a 7 8, 1 6 b f, "))
        assertTrue(s.endsWith("f 2 0 0, 1 5 a d"))
    }

    // the status's hash (the receiver verified the file against it): taken as it is when it is a SHA-256, so the
    // Done screen never reads the file again; anything else is none
    @Test fun theStatusHashIsTakenWhenItIsOne() {
        assertEquals(abc, ShaText.valid(abc))
        assertEquals(abc, ShaText.valid(abc.uppercase()))
        for (bad in listOf(null, "", "null", abc.dropLast(1), abc + "0", abc.replaceFirst('b', 'g'))) assertEquals(null, ShaText.valid(bad))
    }

    // a copy hashes what it writes: a Save before the Done screen's hash is known still gives the history its SHA-256
    @Test fun aCopyGivesTheHashOfWhatItWrote() {
        val data = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val seen = ArrayList<Long>()
        val sha = ShaText.copy(data.inputStream(), out, ByteArray(65_536)) { seen += it }
        assertTrue(out.toByteArray().contentEquals(data))
        assertEquals(ShaText.of(data.inputStream()), sha)
        assertEquals(listOf(65_536L, 131_072L, 196_608L, 200_000L), seen)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ShaText.copy(ByteArray(0).inputStream(), java.io.ByteArrayOutputStream(), ByteArray(16)) {})
    }
}
