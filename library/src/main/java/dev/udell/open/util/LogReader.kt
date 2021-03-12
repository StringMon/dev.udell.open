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

import android.annotation.TargetApi
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.startup.Initializer
import dev.udell.open.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

/**
 * Utility class to grab a copy of the current app's log(cat) and share it.
 */
@Suppress("unused")
class LogReader(context: Context) {
    private val appContext = context.applicationContext
    private val fileOps = FileUtils()

    /**
     * Return the current log as one (big) string.
     *
     * @return the log, or `null` on failure
     */
    suspend fun getLog(): String? {
        val log = getRawLog()
        var baos: ByteArrayOutputStream? = null
        withContext(Dispatchers.Default) {
            runCatching {
                baos = ByteArrayOutputStream(log?.available() ?: 0)
                baos?.use { out ->
                    log?.copyTo(out)
                }
            }.onFailure {
                baos = null
            }
        }
        return baos?.toString()
    }

    /**
     * Make the log available to the system's sharing providers. With no parameters, it'll share the
     * current log as plain text to any app on the system registered to receive such. Supplying
     * parameters will bias the share toward email.
     *
     * @param emailRecipient If supplied, configures the sharing intent for emailing to this address.
     * @param emailHeaders Optional text to prepend to the email.
     * @param shouldZip If true, will compress the log text into a .zip file
     * @return `Boolean` for success
     */
    suspend fun shareLog(
        emailRecipient: String? = null,
        emailHeaders: String? = null,
        shouldZip: Boolean = false
    ): Boolean {
        var useZip: Boolean = shouldZip

        // Grab the log from the system and save it to our working dir
        val targetName = LOG_SUBDIR + '/' + getLogFilePrefix(appContext) +
                DateFormat.format("yyyyMMdd_hhmmss", System.currentTimeMillis()) + ".log"
        var savedName: String?
        withContext(Dispatchers.IO) {
            savedName = getRawLog()?.let { log ->
                fileOps.saveStream(appContext.cacheDir.absolutePath, targetName, log)
            }

            if (shouldZip) {
                savedName?.let { unzippedName ->
                    // Zip it up
                    val zippedName = unzippedName.replace(".log", ".zip")
                    runCatching {
                        Zipper.zip(zippedName, unzippedName)
                        fileOps.deleteFile(unzippedName)
                    }.onSuccess {
                        savedName = zippedName
                    }.onFailure {
                        // Fall back to sharing the unzipped file
                        useZip = false
                    }
                }
            }
        }

        // Ensure that we have a log file to share
        val finalName = savedName ?: return false

        // Prepare the sharing intent

        val emailIntent = makeEmailIntent(
            appContext,
            emailRecipient,
            appContext.getString(R.string.app_name) + " log",
            emailHeaders?.plus('\n')
        ) ?: return false

        if (useZip) {
            emailIntent.type = "application/zip"
        }

        if (emailRecipient != null) {
            emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(emailRecipient.toString()))
        }

        val logUri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".logprovider",
            File(finalName)
        )
        emailIntent.putExtra(Intent.EXTRA_STREAM, logUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            appContext.startActivity(emailIntent)
        } catch (e: ActivityNotFoundException) {
            return false
        }

        return true
    }

    private suspend fun getRawLog(): InputStream? {
        var process: Process? = null
        withContext(Dispatchers.Default) {
            runCatching {
                process = Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-v", "threadtime", "-d"))
            }.onFailure {
                process = null
            }
        }
        return process?.inputStream
    }

    companion object {
        internal const val LOG_SUBDIR = "log"

        /** The log files generated will be named with this value followed by a timestamp */
        internal fun getLogFilePrefix(context: Context): String {
            return context.packageName + "_log_"
        }

        /**
         * Helper function to create an email-the-developer `Intent`. Used to send the log, but
         * can also be used standalone, such as for a "Contact" link.
         */
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        fun makeEmailIntent(
            context: Context,
            recipient: CharSequence?,
            subject: CharSequence,
            body: CharSequence?
        ): Intent? {
            val action: Intent
            if (context.packageManager.hasSystemFeature("org.chromium.arc.device_management")) {
                // Looks like we're running on Chrome OS, so we need a special email intent  

                // CrOS has no generic sharing intent, so repipient is required here
                if (recipient == null) {
                    return null
                }

                // Build the intent
                val mailTo = "mailto:" + recipient + "?subject=" + subject +
                        "&body=" + body
                action = Intent(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setData(
                        Uri.parse(
                            "https://mail.google.com/mail/?extsrc=mailto&url=" +
                                    Uri.encode(mailTo.replace("+", "%2B"))
                        )
                    )

                // CrOS also takes a while to resolve this intent, so let the user know it's underway
                if (Looper.myLooper() == null) {
                    Looper.prepare()
                }
                val progress =
                    Toast.makeText(context, R.string.loading_ellipses, Toast.LENGTH_SHORT)
                progress.setGravity(Gravity.CENTER, 0, 0)
                progress.show()
            } else {
                // Not running on CrOS AFAICT; use a normal Android intent
                action = Intent(Intent.ACTION_SEND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT + Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
            }

            return action
        }
    }
}

/** The `ContentProvider` which makes `LogReader` work. */
class LogProvider : FileProvider()

/** Helper class to delete old logs the next time the app starts. */
@Suppress("unused") // it's referenced in the manifest
class LogDeleter : Initializer<Unit> {
    override fun create(context: Context) {
        // Remove any old log files from the cache dir
        val path = context.cacheDir.absolutePath + '/' + LogReader.LOG_SUBDIR
        File(path).list(PrefixFilter(LogReader.getLogFilePrefix(context)))
            ?.forEach { name ->
                File(path, name).delete()
            }
    }

    // No dependencies on other libraries.
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private class PrefixFilter(val prefix: String) : FilenameFilter {
        override fun accept(dir: File, filename: String) = filename.startsWith(prefix)
    }
}
