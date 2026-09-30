package io.github.proburakelci.codepad

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Reading and writing the files the user picked.
 *
 * Everything goes through the system document picker, which is why this app
 * asks for no permissions at all - not storage, not anything. Android hands
 * back a `content://` uri for the one file the user chose and nothing else on
 * the device is reachable.
 */
object Documents {

    /**
     * Files bigger than this are refused rather than opened.
     *
     * The editor highlights the whole document on every keystroke. A few
     * hundred kilobytes is fine; a 40 MB log file would freeze the app with no
     * way out, and a frozen app looks like a broken app. Better to say no.
     */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** What came back from trying to read a file. */
    sealed class Read {
        data class Ok(val text: String, val name: String?) : Read()
        data class Failed(val reason: String) : Read()
    }

    fun read(resolver: ContentResolver, uri: Uri): Read {
        val name = displayName(resolver, uri)

        val size = sizeOf(resolver, uri)
        if (size != null && size > MAX_BYTES) {
            return Read.Failed(
                "That file is ${size / 1024 / 1024} MB. This editor highlights the whole " +
                    "document as you type, so it only opens files under 2 MB."
            )
        }

        return try {
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                // Read one byte past the limit so a file whose size the
                // provider would not tell us is still caught.
                stream.readAtMost(MAX_BYTES + 1)
            } ?: return Read.Failed("Nothing could be read from that file.")

            if (bytes.size > MAX_BYTES) {
                return Read.Failed(
                    "That file is over 2 MB. This editor highlights the whole document as " +
                        "you type, so it only opens files under 2 MB."
                )
            }

            if (looksBinary(bytes)) {
                return Read.Failed("That does not look like a text file.")
            }

            Read.Ok(bytes.toString(Charsets.UTF_8), name)
        } catch (err: IOException) {
            Read.Failed(err.message ?: "That file could not be read.")
        } catch (err: SecurityException) {
            Read.Failed("This app is no longer allowed to read that file. Open it again.")
        }
    }

    /** Returns null when it worked, or the reason it did not. */
    fun write(resolver: ContentResolver, uri: Uri, text: String): String? =
        try {
            /*
             * "wt" truncates. Without the t, saving a shorter version of a
             * file leaves the tail of the old one behind it, which is a
             * genuinely nasty way to lose work.
             */
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: "That file could not be opened for writing."
            null
        } catch (err: IOException) {
            err.message ?: "That file could not be written."
        } catch (err: SecurityException) {
            "This app is no longer allowed to write to that file. Use Save as."
        }

    fun displayName(resolver: ContentResolver, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } else {
                uri.lastPathSegment
            }
        } catch (err: Exception) {
            uri.lastPathSegment
        } finally {
            cursor?.close()
        }
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
            } else {
                null
            }
        } catch (err: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * A NUL byte in the first few kilobytes means this is not text. Opening a
     * .png as source would fill the editor with rubbish and could take a long
     * time doing it.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        val window = minOf(bytes.size, 8000)
        for (i in 0 until window) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total <= limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total + 1))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }
}
