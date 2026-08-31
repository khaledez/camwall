package net.khaledez.camwall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Belt and braces: the HOME intent-filter already brings the wall up on boot once this is
 * the launcher, but this covers the case where it is installed alongside the Google TV shell.
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
