package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream


object StorageUtils {

    enum class Copy { OK, TOO_BIG, FAILED }

    sealed class Import {
        class Ok(val file: File) : Import()
        /** Over the limit given; nothing was kept. */
        object TooBig : Import()
        object Failed : Import()
    }

    fun importFile(context: Context, targetDir: String, uri: Uri): File? =
        (importFile(context, targetDir, uri, Long.MAX_VALUE) as? Import.Ok)?.file

    /**
     * Copies [uri] into [targetDir] under its own name, unless it is longer
     * than [maxBytes]. Blocking: call it off the main thread.
     */
    fun importFile(context: Context, targetDir: String, uri: Uri, maxBytes: Long): Import {
        val name = queryName(context, uri)
        if(name == null) {
            Timber.e("importFile: name is null")
            return Import.Failed
        }

        val destinationFile = File(targetDir + File.separatorChar + name)
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                when (copyBounded(ins, destinationFile, maxBytes)) {
                    Copy.OK -> {}
                    Copy.TOO_BIG -> return Import.TooBig
                    Copy.FAILED -> return Import.Failed
                }
            }
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            return Import.Failed
        }
        return Import.Ok(destinationFile)
    }

    /** The size the provider reports for [uri], if it knows it. */
    fun querySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (ex: Exception) {
        Timber.w(ex, "querySize")
        null
    }

    fun openInputStreamSafe(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            null
        }
    }

    /**
     * Copies [ins] to [destination], stopping as soon as more than [maxBytes]
     * have come in. A size the provider reports can be missing or wrong, so
     * the count is what decides. A copy cut short, or one that failed, is
     * deleted rather than left behind in the library.
     */
    internal fun copyBounded(ins: InputStream, destination: File, maxBytes: Long): Copy {
        var result = Copy.OK
        try {
            FileOutputStream(destination).use { os ->
                val buffer = ByteArray(4096)
                var total = 0L
                var length: Int
                while (ins.read(buffer).also { length = it } > 0) {
                    total += length
                    if (total > maxBytes) {
                        result = Copy.TOO_BIG
                        break
                    }
                    os.write(buffer, 0, length)
                }
                os.flush()
            }
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            result = Copy.FAILED
        }
        if (result != Copy.OK) destination.delete()
        return result
    }

    fun queryName(context: Context, uri: Uri): String? {
        val returnCursor = context.contentResolver.query(uri, null, null, null, null)
        returnCursor ?: return null
        val nameIndex: Int = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        returnCursor.moveToFirst()
        val name: String = returnCursor.getString(nameIndex)
        returnCursor.close()
        return name
    }
}