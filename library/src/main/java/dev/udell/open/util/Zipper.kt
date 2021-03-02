package dev.udell.open.util

import android.util.Log
import dev.udell.open.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
* Thanks to Jon Simon:
* 		http://www.jondev.net/articles/Zipping_Files_with_Android_(Programmatically)
* and
*	 	http://www.jondev.net/articles/Unzipping_Files_with_Android_(Programmatically)
*/
@Suppress("unused")
object Zipper {
    private const val TAG = "Zipper"
    private val DOLOG = BuildConfig.DEBUG
    private const val BUFFER = 2048
    
    @Throws(IOException::class)
    suspend fun zip(zipFile: String?, vararg srcFiles: String) {
        withContext(Dispatchers.IO) {
            var origin: BufferedInputStream
            val dest = FileOutputStream(zipFile)
            ZipOutputStream(BufferedOutputStream(dest)).use { out ->
                val data = ByteArray(BUFFER)
                for (file in srcFiles) {
                    if (DOLOG) Log.v(TAG, "zip, adding: $file")
                    try {
                        val fi = FileInputStream(file)
                        origin = BufferedInputStream(fi, BUFFER)
                        val entry = ZipEntry(
                            file.substring(
                                file.lastIndexOf("/") + 1
                            )
                        )
                        out.putNextEntry(entry)
                        var count: Int
                        while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                            out.write(data, 0, count)
                        }
                        origin.close()
                    } catch (fnf: FileNotFoundException) {
                        if (DOLOG) Log.w(TAG, "zip, not found:$file")
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    fun unzip(zipFile: String?, location: String) {
        dirChecker(location)
        unzip(FileInputStream(zipFile), location)
    }

    @Throws(IOException::class)
    fun unzip(zipStream: InputStream?, location: String) {
        val zin = ZipInputStream(zipStream)
        var ze: ZipEntry
        while (zin.nextEntry.also { ze = it } != null) {
            if (DOLOG) Log.v(TAG, "unzip, extracting " + ze.name)
            if (ze.isDirectory) {
                dirChecker(location + ze.name)
            } else {
                BufferedOutputStream(
                    FileOutputStream(location + ze.name)
                ).use { out ->
                    var c = zin.read()
                    while (c != -1) {
                        out.write(c)
                        c = zin.read()
                    }
                    zin.closeEntry()
                }
            }
        }
        zin.close()
    }

    private fun dirChecker(dir: String) {
        val f = File(dir)
        if (!f.isDirectory) {
            f.mkdirs()
        }
    }
}
