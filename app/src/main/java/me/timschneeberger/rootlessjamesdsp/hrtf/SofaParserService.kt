package me.timschneeberger.rootlessjamesdsp.hrtf

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException

/** The JNI face of librv4asofa. Loaded only in the isolated helper process. */
object SofaReader {
    init {
        System.loadLibrary("rv4asofa")
    }

    /**
     * Reads the whole of [fd] (and closes it). [info] gets error, library
     * error, taps, measurements, diffuse bins and whether the ears were
     * swapped; [meta] the rate and the nine directions; [strings] title,
     * licence and database name. Returns the pairs then the diffuse average,
     * or null.
     */
    @JvmStatic
    external fun nativeExtract(fd: Int, info: IntArray, meta: FloatArray, strings: Array<String?>): FloatArray?
}

/**
 * Reads .sofa files for the Binaural upmix in a process of its own.
 *
 * Declared with android:isolatedProcess, so it runs under a throwaway user id
 * with no permissions and no access to the app's files; it is handed the file
 * as an open descriptor and hands back only plain arrays. A file built to
 * break the reader can at worst end this process, never the app or the audio
 * running in it.
 */
class SofaParserService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("sofa-reader").apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        if (msg.what != MSG_PARSE) return
        val replyTo = msg.replyTo ?: return
        val out = Bundle()
        @Suppress("DEPRECATION")
        val pfd = msg.data?.getParcelable<ParcelFileDescriptor>(KEY_FD)
        if (pfd == null) {
            out.putIntArray(KEY_INFO, intArrayOf(ERR_NO_FILE, 0, 0, 0, 0, 0))
        } else {
            val info = IntArray(6)
            val meta = FloatArray(1 + BinauralSpeakers.COUNT * 2)
            val strings = arrayOfNulls<String>(3)
            val data = SofaReader.nativeExtract(pfd.detachFd(), info, meta, strings)
            out.putIntArray(KEY_INFO, info)
            out.putFloatArray(KEY_META, meta)
            out.putFloatArray(KEY_DATA, data)
            out.putStringArray(KEY_STRINGS, strings)
        }
        val reply = Message.obtain(null, MSG_RESULT)
        reply.data = out
        try {
            replyTo.send(reply)
        } catch (_: RemoteException) {
        }
    }

    companion object {
        const val MSG_PARSE = 1
        const val MSG_RESULT = 2
        const val KEY_FD = "fd"
        const val KEY_INFO = "info"
        const val KEY_META = "meta"
        const val KEY_DATA = "data"
        const val KEY_STRINGS = "strings"

        /** Codes from libsofareader/sofa_extract.h, plus one of our own. */
        const val ERR_OK = 0
        const val ERR_SIZE = 1
        const val ERR_PARSE = 2
        const val ERR_NOT_HRIR = 3
        const val ERR_DIMS = 4
        const val ERR_MEMORY = 5
        const val ERR_NO_FILE = 100

        /** True in the helper's own process, where the app's start-up must not run. */
        fun isIsolatedProcess(): Boolean {
            // Isolated processes get user ids 99000 to 99999 within the user.
            val appId = android.os.Process.myUid() % 100000
            return appId in 99000..99999
        }
    }
}
