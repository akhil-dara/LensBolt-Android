package com.lensbolt.app

// The received file's SHA-256 for people: on the Done screen and in the history, to compare with the sender's.
// Pure (no Android types).

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object ShaText {
    /** The SHA-256 of [input] as 64 lowercase hex digits. */
    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
    fun of(f: File): String = f.inputStream().use { of(it) }

    /** A hash as the status or a batch page gives it ("sha256"): 64 hex digits, lowercase; anything else null. */
    fun valid(s: String?): String? = s?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    /** Copies [input] to [output] through [buf] and gives the SHA-256 of what it wrote; [onBytes] after each write,
     *  with the bytes written so far. */
    fun copy(input: InputStream, output: OutputStream, buf: ByteArray, onBytes: (Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            output.write(buf, 0, n); md.update(buf, 0, n); done += n
            onBytes(done)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** In groups of four: "9f86 d081 884c ...", two lines of eight groups (the browser's Done screen too). */
    fun groups(hex: String): String = lines(hex, 8)

    /** The groups `perLine` to a line: 8 (two lines), or 4 where eight do not fit; a group is never split. */
    fun lines(hex: String, perLine: Int): String = hex.chunked(4).chunked(perLine).joinToString("\n") { it.joinToString(" ") }

    /** Eight groups a line when a line of eight fits `available` (`width`: a text's measured width), else four. */
    fun perLine(available: Float, width: (String) -> Float): Int =
        if (width("0000 0000 0000 0000 0000 0000 0000 0000") <= available) 8 else 4

    /** What TalkBack reads: the groups one by one, each spelt ("9 f 8 6, d 0 8 1, ..."). */
    fun spoken(hex: String): String = "SHA-256: " + hex.chunked(4).joinToString(", ") { it.toList().joinToString(" ") }
}
