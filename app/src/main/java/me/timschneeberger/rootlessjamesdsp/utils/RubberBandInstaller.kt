package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The optional Rubber Band download.
 *
 * Rubber Band is the best real-time pitch shifter there is, but it is most of a
 * megabyte and most people never open this card, so it is not in the app. This
 * fetches it on request instead, checks it is really the file we built, and
 * puts it somewhere the engine can open it. Removing it is one call and leaves
 * nothing behind.
 *
 * The file is hosted on this project's own releases rather than linked from
 * upstream, so the download does not break the day upstream moves. The matching
 * source is vendored in third_party/rubberband, which is also what the licence
 * requires of anyone handing out a GPL binary.
 */
object RubberBandInstaller {
    /** The release these binaries were built from, and the tag holding them. */
    const val VERSION = "4.0.0"

    /**
     * The first entry in the Method list that needs this download. Everything
     * from here up is Rubber Band; below it are the two built-in methods, which
     * always work.
     */
    const val FIRST_MODE = 2
    private const val TAG = "rubberband-v$VERSION"
    private const val BASE =
        "https://github.com/alienware377/RootlessViPER4Android/releases/download/$TAG"

    /**
     * What each architecture's file must hash to. This is not decoration: a
     * truncated download, a proxy that served an error page, or a substituted
     * file would otherwise be handed straight to dlopen. A mismatch is treated
     * as a failed download, not as a file to keep.
     */
    private val CHECKSUMS = mapOf(
        "arm64-v8a" to "373884a604ec4b130b854230b79f2253d310a453af299c27ca6fcb5ad2ba63c1",
        "armeabi-v7a" to "d58edc5aaa03a89b87953ab7b08a0766a2df13b73ccb1c4ab6fe66445eef89c6",
        "x86" to "02d4ea806549390d5b5bcfeb6bb398b2c5f6be2da4b1e39bb2803031ebe34074",
        "x86_64" to "e573dc89dd81eb0ecef5989f60662344c1d9dd6be95f47ae92146d1b23d12cee",
    )

    /** Roughly what the user is agreeing to download, for the button's summary. */
    val approximateSizeKb: Int
        get() = when (abi) {
            "armeabi-v7a" -> 500
            "arm64-v8a" -> 730
            else -> 770
        }

    /**
     * The first architecture the device reports that we have a build for.
     * SUPPORTED_ABIS is in preference order, so a 64-bit device picks its 64-bit
     * build and only falls back to the 32-bit one if that is all it can run.
     */
    val abi: String?
        get() = Build.SUPPORTED_ABIS.firstOrNull { CHECKSUMS.containsKey(it) }

    val isSupported: Boolean
        get() = abi != null

    private fun dir(context: Context) = File(context.filesDir, "rubberband")

    /** Where the engine is told to look. Null when this device has no build. */
    fun path(context: Context): String? {
        val abi = abi ?: return null
        return File(dir(context), "$VERSION-$abi-librubberband.so").absolutePath
    }

    /**
     * Installed means present AND still the file we expect. Checking the hash on
     * every ask is a few milliseconds against a file this size, and it is the
     * difference between noticing a half-written download and loading one.
     */
    fun isInstalled(context: Context): Boolean {
        val expected = CHECKSUMS[abi ?: return false] ?: return false
        val file = File(path(context) ?: return false)
        return file.isFile && sha256(file) == expected
    }

    /**
     * Fetch and verify. Returns null on success, or a short sentence to show the
     * user on failure. Blocking: call it off the main thread.
     *
     * Downloads beside the real name and moves it into place only once the hash
     * matches, so an interrupted download can never be mistaken for an installed
     * one.
     */
    fun install(context: Context, onProgress: ((Int) -> Unit)? = null): String? {
        val abi = abi ?: return "This device's processor is not one we have a build for."
        val expected = CHECKSUMS[abi] ?: return "No checksum on record for $abi."
        val target = File(path(context)!!)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")

        try {
            val connection = (URL("$BASE/$abi-librubberband.so").openConnection() as HttpURLConnection)
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return "The server answered ${connection.responseCode}. Try again later."
            }
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress?.invoke((done * 100 / total).toInt())
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            partial.delete()
            return "Could not download it: ${e.message ?: e.javaClass.simpleName}"
        }

        if (sha256(partial) != expected) {
            partial.delete()
            return "The download did not arrive intact. Nothing was installed."
        }
        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            return "Could not save it. Is storage full?"
        }
        return null
    }

    /**
     * Delete it again. The engine may still have the library open from earlier
     * in this session - a mapping the audio thread could be inside cannot safely
     * be pulled away - so the caller should say it finishes taking effect when
     * the app next starts. Nothing is left on disk either way.
     */
    fun remove(context: Context): Boolean {
        val folder = dir(context)
        val ok = folder.listFiles()?.all { it.delete() } ?: true
        folder.delete()
        return ok && !isInstalled(context)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
