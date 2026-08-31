package net.khaledez.camwall

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Restarts the wall after a fatal exception, because this is an always-on display that
 * nobody is watching for crashes.
 *
 * The crash that motivated it is not ours to fix: Media3's RTSP receiver treats any byte
 * that is not the '$' interleave marker as the start of an RTSP text message and
 * accumulates it into an unbounded ByteArrayOutputStream until it sees CRLF
 * (RtspMessageChannel.parseNextLine). One desynchronised byte on an interleaved TCP
 * connection turns H.264 payload into a "text line" that never terminates, and the heap
 * is gone — 256 MB in the case observed here. There is no length cap in Media3, and the
 * code is byte-identical from 1.4.1 to 1.8.0.
 *
 * Restarting via AlarmManager rather than in-process: the heap is exhausted at that point,
 * so the only reliable move is to hand the intent to the system and die. The pending
 * activity start relies on the same SYSTEM_ALERT_WINDOW exemption that lets BootReceiver
 * launch the wall.
 */
object CrashRestart {

    private const val TAG = "CamWall"
    private const val RESTART_DELAY_MS = 3_000L

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Keep this path allocation-light: it usually runs with the heap already full.
            try {
                Log.e(TAG, "fatal on ${thread.name}, restarting in ${RESTART_DELAY_MS}ms", error)
            } catch (ignored: Throwable) {
            }
            try {
                val intent = Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val pending = PendingIntent.getActivity(
                    appContext, 0, intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                appContext.getSystemService(AlarmManager::class.java)?.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + RESTART_DELAY_MS,
                    pending
                )
            } catch (e: Throwable) {
                try {
                    Log.e(TAG, "could not schedule restart", e)
                } catch (ignored: Throwable) {
                }
            }
            // Let the platform handler record the tombstone before the process goes.
            try {
                previous?.uncaughtException(thread, error)
            } catch (ignored: Throwable) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
