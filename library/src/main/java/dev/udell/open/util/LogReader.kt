package dev.udell.open.util

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
import java.io.File
import java.io.FilenameFilter
import java.io.IOException

@Suppress("unused")
class LogReader(context: Context) {
    private val appContext = context.applicationContext
    private val fileOps = FileUtils()

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
            savedName = try {
                var process: Process? = null
                runCatching {
                    process = Runtime.getRuntime()
                        .exec(arrayOf("logcat", "-v", "threadtime", "-d"))
                }
                process?.let {
                    fileOps.saveStream(
                        appContext.cacheDir.absolutePath,
                        targetName,
                        it.inputStream
                    )
                }
            } catch (e: IOException) {
                null
            }

            if (shouldZip) savedName?.let { unzipped ->
                // Zip it up
                val zipFileName = unzipped.replace(".log", ".zip")
                try {
                    runCatching {
                        Zipper.zip(zipFileName, unzipped)
                    }
                    fileOps.deleteFile(unzipped)
                    savedName = zipFileName
                } catch (e: IOException) {
                    // Problem zipping up the backup. Fall back to sending the uncompressed log.
                    useZip = false
                }
            }
        }

        if (savedName.isNullOrBlank()) {
            // Log capture failed
            return false
        }

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

        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM,
                savedName?.let { name ->
                    FileProvider.getUriForFile(
                        appContext,
                        appContext.packageName + ".logprovider",
                        File(name)
                    )
                }
            )

        try {
            appContext.startActivity(emailIntent)
        } catch (e: ActivityNotFoundException) {
            return false
        }

        return true
    }

    companion object {
        internal const val LOG_SUBDIR = "log"

        internal fun getLogFilePrefix(context: Context): String {
            return context.packageName + "_log_"
        }

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

class LogProvider : FileProvider()

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

    class PrefixFilter(val prefix: String) : FilenameFilter {
        override fun accept(dir: File, filename: String) = filename.startsWith(prefix)
    }
}
