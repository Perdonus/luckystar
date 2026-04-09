package ru.sosiskibot.luckystar.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val LOG_DIR = "diagnostics"
    private const val LOG_FILE_NAME = "app.log"
    private const val MAX_LOG_CHARS = 400_000
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        i("App", "Logger initialized")
    }

    fun i(tag: String, message: String) {
        append(level = "I", tag = tag, message = message, throwable = null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        append(level = "W", tag = tag, message = message, throwable = throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        append(level = "E", tag = tag, message = message, throwable = throwable)
    }

    fun readLogs(context: Context? = appContext): String {
        val resolved = context ?: return "Логи недоступны: контекст не инициализирован"
        val file = logFile(resolved)
        if (!file.exists()) return "Логи пусты"
        return runCatching { file.readText() }
            .getOrElse { error -> "Не удалось прочитать логи: ${error.message}" }
    }

    fun copyLogsToClipboard(context: Context? = appContext): Boolean {
        val resolved = context ?: return false
        val logs = readLogs(resolved)
        val clipboard = resolved.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("LuckyStar logs", logs))
        i("Logs", "Logs copied to clipboard (${logs.length} chars)")
        return logs.isNotBlank()
    }

    private fun append(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        when (level) {
            "W" -> Log.w(tag, message, throwable)
            "E" -> Log.e(tag, message, throwable)
            else -> Log.i(tag, message, throwable)
        }

        val context = appContext ?: return
        val entry = buildString {
            append(timestamp())
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(':')
            append(' ')
            append(message)
            if (throwable != null) {
                append('\n')
                append(stackTrace(throwable))
            }
            append('\n')
        }

        runCatching {
            synchronized(lock) {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                file.appendText(entry)
                trimIfNeeded(file)
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists()) return
        val text = file.readText()
        if (text.length <= MAX_LOG_CHARS) return
        file.writeText(text.takeLast(MAX_LOG_CHARS))
    }

    private fun logFile(context: Context): File {
        return File(File(context.filesDir, LOG_DIR), LOG_FILE_NAME)
    }

    private fun timestamp(): String = synchronized(formatter) {
        formatter.format(Date())
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }
}
