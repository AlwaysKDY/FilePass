package com.filepass

import android.app.Application
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilePassApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val log = "[$time] Thread: ${thread.name}\n$sw\n"

                // 写入外部存储 /sdcard/filepass_crash.log（无需权限，应用专属）
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash.log").appendText(log)
            } catch (_: Exception) { }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
