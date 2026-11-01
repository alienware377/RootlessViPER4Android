package me.timschneeberger.rootlessjamesdsp.capture

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The privileged half of the Shizuku capture method. Shizuku starts this in a
 * separate process running as the shell user (uid 2000), which on Android 13
 * and newer holds MODIFY_AUDIO_ROUTING.
 *
 * It registers a dynamic audio policy with one loopback mix. Matched app
 * playback is then sent into a virtual "remote submix" device instead of the
 * speaker, so the original is not heard; this process records that device and
 * passes the PCM to the app through a pipe. The app runs its usual effect
 * chain on it and plays the result. This works for apps that forbid capture
 * (Spotify and others), because Android only checks capture policies for the
 * other kind of mix (loopback with render), not for a pure loopback one.
 *
 * Nothing else lives here: the effects, the presets and every file the
 * effects read stay in the app, which this user cannot read anyway.
 *
 * The one rule this class must never break: while the policy is registered,
 * every matched app is silent unless somebody reads the submix. So every way
 * out (stop, failed start, app death, frozen app, recorder error, Shizuku
 * removing the helper) ends in [teardown], which unregisters it.
 *
 * Framework classes used here (AudioPolicy, AudioMix, AudioMixingRule) are
 * system APIs that the public SDK does not include, so they are reached by
 * reflection. If any of it is missing on a device, start() throws and the app
 * falls back to the standard method.
 *
 * Adapted from CaptureUserService in ViPER4Shizuku's capture-shizuku module,
 * Copyright (C) 2026 TheIceJi, GPL-3.0: the start and teardown structure and
 * the shell-attributed recorder for the route follow it closely. The approach
 * was first shown by sk2andy's android-realtime-voice-isolation (MIT, no code
 * taken). See THIRD_PARTY_NOTICES.md.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class ShizukuCaptureUserService(private val context: Context) : IShizukuCaptureService.Stub() {

    /** Shizuku versions that do not pass a Context use this constructor. */
    constructor() : this(fallbackContext())

    private val lock = Any()

    private var policy: Any? = null
    private var mix: Any? = null
    private var record: AudioRecord? = null
    private var pumpThread: Thread? = null
    private var writeEnd: ParcelFileDescriptor? = null
    private var callback: IShizukuCaptureCallback? = null
    private var appToken: IBinder? = null
    private var appDeath: IBinder.DeathRecipient? = null

    @Volatile
    private var running = false
    /**
     * Bumped by every teardown. A pump only keeps going while the generation
     * it started with is current, so the pump of a replaced capture can never
     * write into the pipe of the one that replaced it.
     */
    @Volatile
    private var generation = 0
    @Volatile
    private var lastStatus = "idle"

    /** The app's uid, the only caller allowed in besides Shizuku itself. */
    private val appUid: Int? = try {
        context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
    } catch (e: Exception) {
        Log.e(TAG, "cannot look up the app's uid", e)
        null
    }

    init {
        // Shizuku's process normally has no hidden API restrictions, but some
        // ROMs differ. Lifting them costs nothing when they are already off.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Throwable) {
            Log.w(TAG, "hidden API exemption not applied", e)
        }
    }

    private fun checkCaller() {
        val caller = Binder.getCallingUid()
        if (caller == Process.myUid()) return
        val app = appUid ?: throw SecurityException("app uid unknown")
        // Same app in another user profile has a different uid but the same app id
        if (caller % PER_USER_RANGE != app % PER_USER_RANGE)
            throw SecurityException("uid $caller may not use the capture helper")
    }

    /**
     * Runs [block] as this process rather than as the calling app. Framework
     * code that checks permissions in-process looks at the binder caller
     * during an incoming call, which is the app, and the app does not hold
     * MODIFY_AUDIO_ROUTING. Without this the recorder for the submix is
     * refused even though the policy registered fine.
     */
    private inline fun <T> asSelf(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getUid(): Int {
        checkCaller()
        return Process.myUid()
    }

    override fun isCapturing(): Boolean {
        checkCaller()
        return running
    }

    override fun getStatus(): String {
        checkCaller()
        return "uid=${Process.myUid()} running=$running last=$lastStatus"
    }

    override fun start(
        config: Bundle,
        matchUids: IntArray,
        excludeUids: IntArray,
        token: IBinder,
        cb: IShizukuCaptureCallback,
    ): ParcelFileDescriptor {
        checkCaller()
        return asSelf {
            synchronized(lock) {
                teardown("restart", notify = false)
                startLocked(Request.fromBundle(config), matchUids, excludeUids, token, cb)
            }
        }
    }

    override fun stop() {
        checkCaller()
        asSelf { synchronized(lock) { teardown("stopped by the app", notify = false) } }
    }

    /**
     * Shizuku never ends a helper process by itself. Unbinding with remove,
     * or this app dying while the helper is bound with daemon off, only sends
     * this call, and the helper has to exit on its own (Shizuku-API README,
     * "Stop the User Service"). Without the exit every stop would leave an
     * idle process behind.
     *
     * The route is dropped first. The wait for the lock is bounded so a stuck
     * start cannot keep this process alive: if it runs out, the exit takes
     * the route down anyway, because audioserver releases a policy as soon as
     * the process that registered it dies.
     */
    override fun destroy() {
        checkCaller()
        val cleanup = Thread({
            synchronized(lock) { teardown("Shizuku removed the capture helper", notify = true) }
        }, "ShizukuCaptureDestroy")
        cleanup.start()
        try { cleanup.join(DESTROY_WAIT_MS) } catch (_: InterruptedException) {}
        Log.i(TAG, "capture helper exiting")
        System.exit(0)
    }

    private fun startLocked(
        req: Request,
        matchUids: IntArray,
        excludeUids: IntArray,
        token: IBinder,
        cb: IShizukuCaptureCallback,
    ): ParcelFileDescriptor {
        if (matchUids.isNotEmpty() && excludeUids.isNotEmpty())
            throw fail("match and exclude lists cannot both be used")

        val am = context.getSystemService(AudioManager::class.java)
            ?: throw fail("no audio service")

        val builtMix = try {
            buildMix(req, matchUids, excludeUids)
        } catch (e: Throwable) {
            throw fail("this Android version does not offer the audio routing calls (${e.javaClass.simpleName})", e)
        }
        val builtPolicy = try {
            val builder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
                .getConstructor(Context::class.java)
                .newInstance(context)
            builder.javaClass.getMethod("addMix", builtMix.javaClass).invoke(builder, builtMix)
            builder.javaClass.getMethod("build").invoke(builder)!!
        } catch (e: Throwable) {
            throw fail("could not build the audio policy (${e.javaClass.simpleName})", e)
        }

        val status = try {
            AudioManager::class.java
                .getMethod("registerAudioPolicy", builtPolicy.javaClass)
                .invoke(am, builtPolicy) as Int
        } catch (e: Throwable) {
            throw fail("registering the audio route failed (${unwrap(e)})", e)
        }
        if (status != 0)
            throw fail("Android refused the audio route (status $status, uid ${Process.myUid()})")

        // From here on matched apps play into the route and nowhere else.
        // Anything that fails before the pump runs must take it down again.
        policy = builtPolicy
        mix = builtMix

        var readEnd: ParcelFileDescriptor? = null
        try {
            val ar = createRecorder(builtMix, req)
            record = ar
            if (ar.state != AudioRecord.STATE_INITIALIZED)
                throw IOException("recorder for the route did not initialise (state ${ar.state})")

            val pipe = ParcelFileDescriptor.createPipe()
            readEnd = pipe[0]
            writeEnd = pipe[1]
            growPipe(pipe[1])

            appToken = token
            appDeath = IBinder.DeathRecipient {
                Log.w(TAG, "app died, dropping the route")
                synchronized(lock) { teardown("app died", notify = false) }
            }.also { token.linkToDeath(it, 0) }
            callback = cb

            running = true
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                throw IOException("recorder did not start (state ${ar.recordingState})")

            val out = pipe[1]
            val gen = generation
            pumpThread = Thread({ pump(ar, out, req, gen) }, "ShizukuCapturePump").apply { start() }
            lastStatus = "capturing ${req.sampleRate} Hz enc ${req.encoding}; " +
                    "match=${matchUids.joinToString(",")} exclude=${excludeUids.joinToString(",")}"
            Log.i(TAG, lastStatus)
            return readEnd
        } catch (e: Throwable) {
            try { readEnd?.close() } catch (_: Exception) {}
            teardown("start failed", notify = false)
            throw fail("could not record the route (${unwrap(e)})", e)
        }
    }

    private fun pump(ar: AudioRecord, out: ParcelFileDescriptor, req: Request, gen: Int) {
        val frameBytes = if (req.encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
        val buffer = ByteBuffer.allocateDirect(req.readFrames * frameBytes)
        val current = { running && generation == gen }
        val writer = CapturePipeWriter(out.fileDescriptor, req.stallTimeoutMs, current)
        var reason = "capture ended"
        try {
            while (current()) {
                buffer.clear()
                val read = ar.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    reason = "recorder error $read"
                    break
                }
                if (read == 0) continue
                buffer.position(0)
                buffer.limit(read)
                if (!writer.writeAll(buffer)) {
                    reason = if (current()) "the app stopped reading for ${req.stallTimeoutMs} ms" else "stopped"
                    break
                }
            }
        } catch (e: IOException) {
            reason = if (current()) "the app closed the capture pipe" else "stopped"
        } catch (e: Throwable) {
            reason = "capture failed: ${unwrap(e)}"
            Log.e(TAG, "pump failed", e)
        } finally {
            synchronized(lock) {
                // Only tear down the capture this pump belongs to. A restart
                // may already have replaced it.
                if (current() && record === ar)
                    teardown(reason, notify = true)
            }
        }
    }

    /**
     * Drops the route and frees everything. Idempotent. Must hold [lock].
     * The policy goes first: that is what puts the audio back on the speaker.
     */
    private fun teardown(reason: String, notify: Boolean) {
        val hadSomething = policy != null || running || record != null
        running = false
        generation++

        policy?.let { p ->
            try {
                val am = context.getSystemService(AudioManager::class.java)
                AudioManager::class.java.getMethod("unregisterAudioPolicy", p.javaClass).invoke(am, p)
            } catch (e: Throwable) {
                Log.e(TAG, "unregisterAudioPolicy failed, trying the async variant", e)
                try {
                    val am = context.getSystemService(AudioManager::class.java)
                    AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", p.javaClass).invoke(am, p)
                } catch (e2: Throwable) {
                    Log.e(TAG, "could not unregister the audio route", e2)
                }
            }
        }
        policy = null
        mix = null

        appToken?.let { t -> appDeath?.let { try { t.unlinkToDeath(it, 0) } catch (_: Exception) {} } }
        appToken = null
        appDeath = null

        val ar = record
        record = null
        if (ar != null) {
            try { ar.stop() } catch (_: Exception) {}
        }

        writeEnd?.let { try { it.close() } catch (_: Exception) {} }
        writeEnd = null

        val pump = pumpThread
        pumpThread = null
        if (pump != null && pump !== Thread.currentThread()) {
            pump.interrupt()
        }
        if (ar != null) {
            try { ar.release() } catch (_: Exception) {}
        }

        val cb = callback
        callback = null
        if (notify && cb != null && hadSomething) {
            try { cb.onStopped(reason) } catch (_: Exception) {}
        }

        if (hadSomething) {
            lastStatus = "stopped: $reason"
            Log.i(TAG, lastStatus)
        }
    }

    /** Same attributes as AudioPolicy.createAudioRecordSink, with a recorder this uid is allowed to own. */
    @SuppressLint("MissingPermission")
    private fun createRecorder(mix: Any, req: Request): AudioRecord {
        val registration = mix.javaClass.getMethod("getRegistration").invoke(mix) as String

        val attrs = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java
            .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attrs, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        addTag.invoke(attrs, "addr=$registration")
        // Records the submix at full scale. The media volume is then applied
        // once, by the app's own output track, instead of twice.
        addTag.invoke(attrs, "fixedVolume")

        val format = AudioFormat.Builder()
            .setSampleRate(req.sampleRate)
            .setEncoding(req.encoding)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        // The context Shizuku gives us carries uid 2000 together with this
        // app's package name, a pair audioserver rejects. Attribute the
        // recorder to the package that really owns uid 2000.
        val uid = Process.myUid()
        val pkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "com.android.shell"
        val source = AttributionSource.Builder(uid)
            .setPackageName(pkg)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setPid(Process.myPid()) }
            .build()
        val attributed = object : ContextWrapper(context) {
            override fun getAttributionSource(): AttributionSource = source
        }

        val minBuffer = AudioRecord.getMinBufferSize(req.sampleRate, AudioFormat.CHANNEL_IN_STEREO, req.encoding)
        val frameBytes = if (req.encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
        val builder = AudioRecord.Builder()
            .setContext(attributed)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, req.readFrames * frameBytes * 2))
        AudioRecord.Builder::class.java
            .getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, attrs.build())
        return builder.build()
    }

    private fun buildMix(req: Request, matchUids: IntArray, excludeUids: IntArray): Any {
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val matchUsage = staticInt(ruleClass, "RULE_MATCH_ATTRIBUTE_USAGE", 1)
        val matchUid = staticInt(ruleClass, "RULE_MATCH_UID", 4)

        val rule = ruleBuilderClass.getConstructor().newInstance()
        val add = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        val exclude = ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in req.usages)
            add.invoke(rule, matchUsage, AudioAttributes.Builder().setUsage(usage).build())
        for (uid in matchUids)
            add.invoke(rule, matchUid, Integer.valueOf(uid))
        for (uid in excludeUids)
            exclude.invoke(rule, matchUid, Integer.valueOf(uid))
        val builtRule = ruleBuilderClass.getMethod("build").invoke(rule)!!

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val loopBack = staticInt(mixClass, "ROUTE_FLAG_LOOP_BACK", 2)
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(builtRule)
        val format = AudioFormat.Builder()
            .setSampleRate(req.sampleRate)
            .setEncoding(req.encoding)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        // Loopback only, never with render: only this kind skips the capture
        // policy checks, and it also takes the audio off the speaker.
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, loopBack)
        return mixBuilderClass.getMethod("build").invoke(mixBuilder)!!
    }

    /** Bigger than the 64 KB default, so a short hiccup in the app does not stall the recorder. */
    private fun growPipe(pfd: ParcelFileDescriptor) {
        try {
            Os.fcntlInt(pfd.fileDescriptor, F_SETPIPE_SZ, PIPE_SIZE)
        } catch (e: Throwable) {
            Log.d(TAG, "pipe size left at default: ${e.message}")
        }
    }

    // Only runtime exceptions travel back over binder to the app. Anything
    // else would reach it as a null pipe with no reason given.
    private fun fail(message: String, cause: Throwable? = null): IllegalStateException {
        Log.e(TAG, message, cause)
        lastStatus = "start failed: $message"
        return IllegalStateException(message, cause)
    }

    /** A Request travels as a Bundle so both processes agree without a shared Parcelable. */
    class Request(
        val sampleRate: Int,
        val encoding: Int,
        val readFrames: Int,
        val stallTimeoutMs: Long,
        val usages: IntArray,
    ) {
        fun toBundle() = Bundle().apply {
            putInt(KEY_RATE, sampleRate)
            putInt(KEY_ENCODING, encoding)
            putInt(KEY_FRAMES, readFrames)
            putLong(KEY_STALL, stallTimeoutMs)
            putIntArray(KEY_USAGES, usages)
        }

        companion object {
            private const val KEY_RATE = "rate"
            private const val KEY_ENCODING = "encoding"
            private const val KEY_FRAMES = "frames"
            private const val KEY_STALL = "stall_ms"
            private const val KEY_USAGES = "usages"

            /**
             * The same usages the screen capture method takes. Calls, alarms,
             * ringtones and notifications are never routed.
             */
            val DEFAULT_USAGES = intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
            )

            fun fromBundle(b: Bundle) = Request(
                sampleRate = b.getInt(KEY_RATE, 48000),
                encoding = b.getInt(KEY_ENCODING, AudioFormat.ENCODING_PCM_FLOAT),
                readFrames = b.getInt(KEY_FRAMES, 1024).coerceIn(64, 16384),
                stallTimeoutMs = b.getLong(KEY_STALL, 1500L).coerceIn(250L, 10000L),
                usages = b.getIntArray(KEY_USAGES) ?: DEFAULT_USAGES,
            )
        }
    }

    companion object {
        private const val TAG = "RV4AShizukuCapture"
        private const val PER_USER_RANGE = 100000
        private const val F_SETPIPE_SZ = 1031
        private const val PIPE_SIZE = 256 * 1024
        private const val DESTROY_WAIT_MS = 1000L

        private fun staticInt(cls: Class<*>, name: String, fallback: Int): Int =
            try { cls.getField(name).getInt(null) } catch (_: Throwable) { fallback }

        private fun unwrap(e: Throwable): String {
            var t: Throwable = e
            while ((t is java.lang.reflect.InvocationTargetException) && t.cause != null)
                t = t.cause!!
            return "${t.javaClass.simpleName}: ${t.message}"
        }

        private fun fallbackContext(): Context {
            val at = Class.forName("android.app.ActivityThread")
            (try { at.getMethod("currentApplication").invoke(null) as Context? } catch (_: Throwable) { null })
                ?.let { return it }
            val thread = try { at.getMethod("currentActivityThread").invoke(null) } catch (_: Throwable) { null }
                ?: at.getMethod("systemMain").invoke(null)
            return at.getMethod("getSystemContext").invoke(thread) as Context
        }
    }
}
