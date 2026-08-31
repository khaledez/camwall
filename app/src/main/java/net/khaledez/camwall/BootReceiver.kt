package net.khaledez.camwall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the wall up after boot while leaving the Google TV launcher in place.
 *
 * Taking over HOME does not work on this device: disabling com.google.android.apps.tv.
 * launcherx drops Google TV into setupwraith/.RecoveryActivity rather than promoting the
 * next HOME candidate, and with ADB not surviving a reboot that is unrecoverable.
 *
 * Android 10+ blocks activity starts from a receiver (logcat: BAL_BLOCK), so the app needs
 * the SYSTEM_ALERT_WINDOW appop granted or this start is silently dropped. See the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }
}
