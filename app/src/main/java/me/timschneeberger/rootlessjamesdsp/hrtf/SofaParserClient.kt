package me.timschneeberger.rootlessjamesdsp.hrtf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Asks the isolated helper to read one .sofa file and waits for the answer. */
object SofaParserClient {
    enum class Failure { NOT_A_SOFA_FILE, NOT_A_HEAD_SET, TOO_BIG, OUT_OF_RANGE, CRASHED, TIMED_OUT, NO_HELPER }

    sealed class Outcome {
        class Ok(val set: RawHrtfSet) : Outcome()
        class Failed(val why: Failure, val detail: String = "") : Outcome()
    }

    /**
     * Blocks until the helper answers, it dies, or [timeoutMs] passes. Must
     * not run on the main thread, which delivers the connection. Takes
     * ownership of [pfd] and closes it.
     */
    fun parse(context: Context, pfd: ParcelFileDescriptor, timeoutMs: Long = 30_000): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "SofaParserClient.parse on the main thread" }
        val thread = HandlerThread("sofa-client").apply { start() }
        val done = CountDownLatch(1)
        val result = AtomicReference<Outcome?>(null)
        fun finish(o: Outcome) {
            if (result.compareAndSet(null, o)) done.countDown()
        }
        val replies = Messenger(Handler(thread.looper) { msg ->
            if (msg.what == SofaParserService.MSG_RESULT) finish(decode(msg.data))
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) { finish(Outcome.Failed(Failure.NO_HELPER)); return }
                try {
                    binder.linkToDeath({ finish(Outcome.Failed(Failure.CRASHED)) }, 0)
                    val m = Message.obtain(null, SofaParserService.MSG_PARSE)
                    m.replyTo = replies
                    m.data = Bundle().apply { putParcelable(SofaParserService.KEY_FD, pfd) }
                    Messenger(binder).send(m)
                } catch (e: RemoteException) {
                    finish(Outcome.Failed(Failure.CRASHED, e.toString()))
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) = finish(Outcome.Failed(Failure.CRASHED))
            override fun onBindingDied(name: ComponentName?) = finish(Outcome.Failed(Failure.CRASHED))
            override fun onNullBinding(name: ComponentName?) = finish(Outcome.Failed(Failure.NO_HELPER))
        }
        var bound = false
        try {
            bound = context.bindService(Intent(context, SofaParserService::class.java),
                connection, Context.BIND_AUTO_CREATE)
            if (!bound) finish(Outcome.Failed(Failure.NO_HELPER))
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) finish(Outcome.Failed(Failure.TIMED_OUT))
        } catch (e: Exception) {
            Timber.e(e, "SOFA helper")
            finish(Outcome.Failed(Failure.NO_HELPER, e.toString()))
        } finally {
            if (bound) try { context.unbindService(connection) } catch (_: Exception) {}
            thread.quitSafely()
            try { pfd.close() } catch (_: Exception) {}
        }
        return result.get() ?: Outcome.Failed(Failure.NO_HELPER)
    }

    private fun decode(b: Bundle?): Outcome {
        val info = b?.getIntArray(SofaParserService.KEY_INFO)
            ?: return Outcome.Failed(Failure.CRASHED)
        when (info[0]) {
            SofaParserService.ERR_OK -> {}
            SofaParserService.ERR_SIZE -> return Outcome.Failed(Failure.TOO_BIG)
            SofaParserService.ERR_PARSE -> return Outcome.Failed(Failure.NOT_A_SOFA_FILE, "code ${info[1]}")
            SofaParserService.ERR_NOT_HRIR -> return Outcome.Failed(Failure.NOT_A_HEAD_SET, "code ${info[1]}")
            else -> return Outcome.Failed(Failure.OUT_OF_RANGE, "error ${info[0]}")
        }
        val taps = info[2]
        val bins = info[4]
        val meta = b.getFloatArray(SofaParserService.KEY_META)
        val data = b.getFloatArray(SofaParserService.KEY_DATA)
        val strings = b.getStringArray(SofaParserService.KEY_STRINGS)
        val n = BinauralSpeakers.COUNT * 2 * taps
        if (meta == null || data == null || taps <= 0 || bins < 0 || data.size != n + bins ||
            meta.size != 1 + BinauralSpeakers.COUNT * 2)
            return Outcome.Failed(Failure.OUT_OF_RANGE, "reply sizes")
        return Outcome.Ok(RawHrtfSet(
            fs = meta[0],
            taps = taps,
            hrir = data.copyOfRange(0, n),
            diffuse = data.copyOfRange(n, n + bins),
            dirs = meta.copyOfRange(1, meta.size),
            measurements = info[3],
            title = strings?.getOrNull(0).orEmpty(),
            license = strings?.getOrNull(1).orEmpty(),
            database = strings?.getOrNull(2).orEmpty(),
        ))
    }
}
