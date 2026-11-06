package net.khaledez.camwall

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Brings the wall back after it dies, because this is an always-on display that nobody is
 * watching for crashes. Two ways it dies, so two mechanisms:
 *
 * - **Java crashes** (in practice an OutOfMemoryError on the playback thread — see the
 *   README for how Media3's RTSP stack fills the heap): the uncaught-exception handler
 *   schedules a restart and kills the process. In-process recovery is not an option with
 *   the heap gone, so the intent is handed to the system.
 * - **Native aborts** (SIGABRT in MediaCodec, e.g. "could not create MediaCodec.BufferInfo
 *   object" on a full heap) skip every Java handler. For those, the wall keeps a restart
 *   permanently scheduled a couple of minutes ahead and pushes it back every minute while
 *   it runs ([keepAlive]). If the process vanishes, the alarm fires.
 *
 * Both rely on the same SYSTEM_ALERT_WINDOW exemption that lets BootReceiver start the
 * activity from the background.
 */
object CrashRestart {

    private const val TAG = "CamWall"
    private const val CRASH_RESTART_MS = 3_000L
    private const val KEEP_ALIVE_RESTART_MS = 150_000L

    private var alarms: AlarmManager? = null
    private var restart: PendingIntent? = null

    fun install(context: Context) {
        // Once per process: every activity re-creation would otherwise wrap another handler.
        if (restart != null) return

        val appContext = context.applicationContext
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // Built up front: the crash path usually runs with the heap already full.
        restart = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        alarms = appContext.getSystemService(AlarmManager::class.java)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Log.e(TAG, "fatal on ${thread.name}, restarting in ${CRASH_RESTART_MS}ms", error)
            } catch (ignored: Throwable) {
            }
            scheduleRestart(CRASH_RESTART_MS)
            // Let the platform handler record the tombstone before the process goes.
            try {
                previous?.uncaughtException(thread, error)
            } catch (ignored: Throwable) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** Called every minute while the wall is on screen. */
    fun keepAlive() = scheduleRestart(KEEP_ALIVE_RESTART_MS)

    /** The wall left the screen on purpose (BACK, another app, TV off): don't bring it back. */
    fun cancel() {
        val pending = restart ?: return
        alarms?.cancel(pending)
    }

    private fun scheduleRestart(delayMs: Long) {
        val pending = restart ?: return
        try {
            alarms?.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pending)
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "could not schedule restart", e)
            } catch (ignored: Throwable) {
            }
        }
    }
}
