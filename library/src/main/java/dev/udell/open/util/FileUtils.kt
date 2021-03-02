package dev.udell.open.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

@Suppress("MemberVisibilityCanBePrivate", "unused")
class FileUtils {

    /**
     * A generic interface for monitoring the progress of a long-running data operation.
     */
    abstract class ProgressListener {
        /**
         * A callback indicating the current progress of the operation
         * @param kbytes How many kB of the data has been processed
         */
        abstract fun onProgress(kbytes: Float)

        /**
         * Clients may call this to cancel the operation before completion. No guarantee is made
         * of its success or immediacy.
         */
        fun cancel() {
            isCancelled = true
        }

        /**
         * Whether a cancel request has been issued.
         */
        var isCancelled: Boolean = false
            private set
    }

    /**
     * Prepare a directory for use as internal app storage. Creates the path it if necessary,
     * and marks it to be excluded from user-space media indices.
     *
     * @param path A `File` object specifying the directory to initialize. If `path` specifies a
     *             file (not just a directory), its parent directory is used.
     * @return Boolean Whether the directory was successfully created
     */
    suspend fun initDirectory(path: File): Boolean {
        val dir: File? = if (path.isDirectory) {
            path
        } else {
            path.parentFile
        }
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            // Directory doesn't exist, and we couldn't create it
            return false
        }

        // Ensure dir has a .nomedia file to tell the OS that the user doesn't need to see it 
        val nomedia = File(dir.absolutePath, ".nomedia")
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile()
            } catch (e: IOException) {
                // Ignore error; .nomedia isn't critical
            }
        }
        return true
    }

    /**
     * Delete a file or directory from device storage.
     *
     * @param pathname The fully-qualified path and name of the file to delete
     * @return A `Boolean` indicating whether the file is now gone from storage: successfully
     *         deleted, or wasn't found in the first place.
     */
    suspend fun deleteFile(pathname: CharSequence): Boolean {
        return deleteFile(File(pathname.toString()))
    }

    suspend fun deleteFile(condemned: File): Boolean {
        var result = true
        return if (condemned.exists()) {
            if (condemned.isDirectory) {
                // Recurse
                val path = condemned.absolutePath + File.separator
                condemned.list()?.forEach { child ->
                    result = deleteFile(path + child) && result
                }
            }
            condemned.delete() && result
        } else {
            true
        }
    }

    /**
     * Write the supplied data stream to a file in device storage.
     *
     * @param path The directory in which to save the data. Will create this directory if needed.
     * @param name The name of the file within that directory to create with the data.
     * @param stream The data to save. Can be any sort of `InputStream` (text, raw bytes, etc).
     * @param listener An optional `ProgressListener` to be notified every 10 kB as the file is written.
     * @return The fully-qualified pathname of the file created, or `null` on failure.
     */
    suspend fun saveStream(
            path: CharSequence,
            name: CharSequence,
            stream: InputStream,
            listener: ProgressListener? = null
    ): String? {
        // Combine the given path + name in case the name has its own embedded path segment
        val pathName = File(path.toString() + File.separator + name)
        var result: String? = pathName.toString()
        try {
            if (!initDirectory(pathName)) {
                // Can't create path
                return null
            }

            FileOutputStream(pathName).use { fos ->
                var chunk: Int
                var total = 0
                val buffer = ByteArray(10240)
                do {
                    chunk = stream.read(buffer)
                    if (chunk > 0) {
                        fos.write(buffer, 0, chunk)
                        if (listener?.isCancelled == true) {
                            throw IOException("Interrupted by listener")
                        } else {
                            total += chunk
                            listener?.onProgress(total / 1024f)
                        }
                    }
                } while (chunk != -1)
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: IOException) {
            // Interrupted, or failed to create file - make sure there's no incomplete remnant left behind
            deleteFile(pathName)
            result = null
        }

        return result
    }
}
