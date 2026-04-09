package ru.sosiskibot.luckystar

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess
import ru.sosiskibot.luckystar.diag.AppLogger

class LuckyStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AppLogger.init(this)
        AppLogger.i("App", "Application created")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e(
                tag = "Crash",
                message = "Uncaught exception on thread=${thread.name}",
                throwable = throwable,
            )
            previousHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
