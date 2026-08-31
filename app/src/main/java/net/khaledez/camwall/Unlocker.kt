package net.khaledez.camwall

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.PasswordAuthentication
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fires a door-release HTTP request. Dahua VTO stations want:
 *   /cgi-bin/accessControl.cgi?action=openDoor&channel=1&UserID=101&Type=Remote
 * but the URL is configuration, so any vendor's endpoint works.
 *
 * HttpURLConnection handles the digest challenge itself via a global Authenticator;
 * we key credentials by host so several door stations can differ.
 */
object Unlocker {

    private const val TAG = "CamWall"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val credentials = HashMap<String, Pair<String, String>>()

    init {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val c = credentials[requestingHost] ?: return null
                return PasswordAuthentication(c.first, c.second.toCharArray())
            }
        })
    }

    /** Calls [onResult] on the main thread with a short human-readable outcome. */
    fun open(unlock: Unlock, onResult: (Boolean, String) -> Unit) {
        io.execute {
            var ok = false
            var message: String
            var conn: HttpURLConnection? = null
            try {
                val url = URL(unlock.url)
                credentials[url.host] = unlock.user to unlock.password
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                val body = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText()?.trim().orEmpty()
                } catch (e: Exception) {
                    ""
                }
                // Dahua answers a bare "OK" on success.
                ok = code in 200..299 && !body.contains("Error", ignoreCase = true)
                message = if (ok) "Lock released" else "Failed: HTTP $code ${body.take(60)}"
                Log.i(TAG, "unlock ${url.host} -> $code ${body.take(120)}")
            } catch (e: Exception) {
                message = "Failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"
                Log.e(TAG, "unlock failed", e)
            } finally {
                conn?.disconnect()
            }
            val finalMessage = message
            val finalOk = ok
            main.post { onResult(finalOk, finalMessage) }
        }
    }
}
