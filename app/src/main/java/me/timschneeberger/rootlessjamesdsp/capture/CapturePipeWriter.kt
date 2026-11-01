package me.timschneeberger.rootlessjamesdsp.capture

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Writes captured PCM into the app's pipe without ever blocking for long.
 *
 * This is the watchdog for a frozen app. While the loopback route is
 * registered, every matched app plays into it and nothing else, so if the app
 * is alive but has stopped reading (stuck, frozen, debugger) the phone goes
 * silent. Its binder never dies in that case, so death notifications do not
 * help. A pipe that stays full for [stallTimeoutMs] is taken as that state,
 * and the caller drops the route so music falls back to the speaker.
 *
 * Adapted from PipeWriter in ViPER4Shizuku's capture-shizuku module,
 * Copyright (C) 2026 TheIceJi, GPL-3.0. See THIRD_PARTY_NOTICES.md.
 */
internal class CapturePipeWriter(
    private val fd: FileDescriptor,
    private val stallTimeoutMs: Long,
    private val keepGoing: () -> Boolean,
) {
    private val pollFd = StructPollfd().apply {
        fd = this@CapturePipeWriter.fd
        events = OsConstants.POLLOUT.toShort()
    }
    private val pollFds = arrayOf(pollFd)

    /**
     * Writes everything left in [buffer]. Returns false if the pipe stayed
     * full for the stall timeout or [keepGoing] turned false. Throws when the
     * reading end is gone.
     */
    fun writeAll(buffer: ByteBuffer): Boolean {
        var fullSince = 0L
        while (buffer.hasRemaining()) {
            if (!keepGoing())
                return false

            pollFd.revents = 0
            val ready = try {
                Os.poll(pollFds, POLL_INTERVAL_MS)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw IOException("poll on capture pipe failed", e)
            }

            if (ready == 0) {
                val now = SystemClock.elapsedRealtime()
                if (fullSince == 0L) fullSince = now
                if (now - fullSince >= stallTimeoutMs) return false
                continue
            }

            val gone = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
            if (pollFd.revents.toInt() and gone != 0)
                throw IOException("capture pipe reader went away (revents=${pollFd.revents})")

            // Once poll says the pipe is writable, a write of at most PIPE_BUF
            // bytes goes through in one piece without blocking.
            val end = buffer.limit()
            buffer.limit(minOf(end, buffer.position() + PIPE_BUF))
            try {
                Os.write(fd, buffer)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR && e.errno != OsConstants.EAGAIN)
                    throw IOException("write to capture pipe failed", e)
            } finally {
                buffer.limit(end)
            }
            fullSince = 0L
        }
        return true
    }

    private companion object {
        const val PIPE_BUF = 4096
        const val POLL_INTERVAL_MS = 50
    }
}
