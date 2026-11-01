package me.timschneeberger.rootlessjamesdsp.hrtf

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The small file a prepared set is kept in, so a .sofa file is read once and
 * not on every settings change. About 18 KB for the built-in set.
 *
 * Layout (big-endian): magic, version, rate, taps, centre offset, whether it
 * was evened out, the 18 direction values, title, licence, database name,
 * then the responses.
 */
object HrtfSetFile {
    private const val MAGIC = 0x52563448 // "RV4H"
    private const val VERSION = 1
    private const val MAX_TEXT = 4096

    fun write(set: PreparedHrtfSet, out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeFloat(set.fs)
        d.writeInt(set.taps)
        d.writeInt(set.cOff)
        d.writeBoolean(set.evenedOut)
        set.dirs.forEach(d::writeFloat)
        d.writeUTF(set.title.take(MAX_TEXT))
        d.writeUTF(set.license.take(MAX_TEXT))
        d.writeUTF(set.database.take(MAX_TEXT))
        set.hrir.forEach(d::writeFloat)
        d.flush()
    }

    /** Null for anything that is not a complete file of this version. */
    fun read(input: InputStream): PreparedHrtfSet? = try {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC || d.readInt() != VERSION) null
        else {
            val fs = d.readFloat()
            val taps = d.readInt()
            val cOff = d.readInt()
            val even = d.readBoolean()
            if (!(fs >= 8000f && fs <= 384000f) || taps !in 16..4096 || cOff !in 0 until taps) null
            else {
                val dirs = FloatArray(BinauralSpeakers.COUNT * 2) { d.readFloat() }
                val title = d.readUTF()
                val license = d.readUTF()
                val database = d.readUTF()
                val hrir = FloatArray(BinauralSpeakers.COUNT * 2 * taps) { d.readFloat() }
                if (d.read() != -1 || hrir.any { !it.isFinite() }) null
                else PreparedHrtfSet(fs, taps, cOff, hrir, dirs, even, title, license, database)
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
