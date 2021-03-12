package dev.udell.open.util
/*
    MIT License
    
    Copyright (c) 2021 Sterling C. Udell
    
    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:
    
    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.
    
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
*/

import android.util.Log
import dev.udell.open.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Utility object for de/compressing files, mostly helpers around the platform's Zip* classes.
 *
 * Original code inspired by Jon Simon, with thanks:
 * 		http://www.jondev.net/articles/Zipping_Files_with_Android_(Programmatically)
 *	 	http://www.jondev.net/articles/Unzipping_Files_with_Android_(Programmatically)
 */
@Suppress("unused")
object Zipper {
    private const val TAG = "Zipper"
    private val DOLOG = BuildConfig.DEBUG
    private const val BUFFER = 2048

    /**
     * Compress one or more input files into a single zip file.
     *
     * @param zipFile the name of the zip file to create
     * @param srcFiles name(s) of file(s) to compress
     */
    @Throws(IOException::class)
    suspend fun zip(zipFile: String?, vararg srcFiles: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = FileOutputStream(zipFile)
                ZipOutputStream(BufferedOutputStream(dest))
                    .use { out ->
                        val data = ByteArray(BUFFER)
                        for (file in srcFiles) {
                            if (DOLOG) Log.v(TAG, "zip, adding: $file")
                            val origin = BufferedInputStream(FileInputStream(file), BUFFER)
                            val entry = ZipEntry(file.substring(file.lastIndexOf("/") + 1))
                            out.putNextEntry(entry)
                            var count: Int
                            while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                                out.write(data, 0, count)
                            }
                            origin.close()
                        }
                    }
            }.getOrThrow()
        }
    }

    /**
     * Decompress from a file.
     *
     * @param zipFile the name of the zip file to decompress
     * @param location the pathname of a directory to receive files decompressed from the source.
     *                 Will be created if it doesn't exist.
     */
    @Throws(IOException::class)
    suspend fun unzip(zipFile: String?, location: String) {
        dirChecker(location)
        withContext(Dispatchers.IO) {
            runCatching {
                unzip(FileInputStream(zipFile), location)
            }.getOrThrow()
        }
    }

    /**
     * Decompress from a stream.
     *
     * @param zipStream a stream of compressed data
     * @param location the pathname of a directory to receive files decompressed from the source.
     *                 Will be created if it doesn't exist.
     */
    @Throws(IOException::class)
    suspend fun unzip(zipStream: InputStream?, location: String) {
        val zipIn = ZipInputStream(zipStream)
        withContext(Dispatchers.IO) {
            runCatching {
                while (true) {
                    val zipEntry = zipIn.nextEntry ?: break
                    if (DOLOG) Log.v(TAG, "unzip, extracting " + zipEntry.name)

                    if (zipEntry.isDirectory) {
                        dirChecker(location + zipEntry.name)
                        continue
                    }

                    BufferedOutputStream(FileOutputStream(location + zipEntry.name))
                        .use { out ->
                            var c = zipIn.read()
                            while (c != -1) {
                                out.write(c)
                                c = zipIn.read()
                            }
                            zipIn.closeEntry()
                        }
                }
                zipIn.close()
            }.getOrThrow()
        }
    }

    private fun dirChecker(dir: String) {
        val f = File(dir)
        if (!f.isDirectory) {
            f.mkdirs()
        }
    }
}
