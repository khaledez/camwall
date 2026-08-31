package net.khaledez.camwall

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Fires a door-release HTTP request. Dahua VTO stations want:
 *   /cgi-bin/accessControl.cgi?action=openDoor&channel=1&UserID=101&Type=Remote
 * but the URL is configuration, so any vendor's endpoint works.
 *
 * Digest auth is implemented here rather than left to java.net.Authenticator: Android's
 * HttpURLConnection is backed by OkHttp, which dropped Digest support, so an Authenticator
 * only ever satisfies Basic challenges. These door stations require Digest and reject
 * Basic outright, so relying on the platform throws IOException.
 *
 * Follows the scheme proven in github.com/khaledez/camonitor (digest.go): parameter order
 * matching curl --digest, qop and nc unquoted, no algorithm parameter. A camera given the
 * wrong form answers "401 Invalid Authority!", which reads like a permissions problem but
 * is an auth-response mismatch.
 */
object Unlocker {

    private const val TAG = "CamWall"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Comma-separated key=value pairs, where quoted values may themselves contain commas. */
    private fun parseChallenge(header: String): Map<String, String> {
        val body = header.trim().removePrefix("Digest ").removePrefix("digest ")
        val out = HashMap<String, String>()
        var inQuotes = false
        var start = 0
        fun flush(end: Int) {
            val part = body.substring(start, end).trim()
            val eq = part.indexOf('=')
            if (eq > 0) {
                out[part.substring(0, eq).trim().lowercase()] =
                    part.substring(eq + 1).trim().removeSurrounding("\"")
            }
        }
        body.forEachIndexed { i, c ->
            when (c) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) { flush(i); start = i + 1 }
            }
        }
        flush(body.length)
        return out
    }

    /** "auth" if advertised — the only qop we support — otherwise "" for the legacy form. */
    private fun pickQop(list: String?): String =
        list.orEmpty().split(",").map { it.trim() }.firstOrNull { it == "auth" }.orEmpty()

    /** [uri] must be the request target: path plus query, not the absolute URL. */
    private fun authHeader(header: String, method: String, uri: String,
                           user: String, password: String): String {
        val c = parseChallenge(header)
        val realm = c["realm"].orEmpty()
        val nonce = c["nonce"].orEmpty()
        val opaque = c["opaque"].orEmpty()
        require(realm.isNotEmpty() && nonce.isNotEmpty()) { "challenge missing realm or nonce" }

        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")

        val parts = mutableListOf(
            """username="$user"""", """realm="$realm"""",
            """nonce="$nonce"""", """uri="$uri""""
        )
        val qop = pickQop(c["qop"])
        val response: String
        if (qop.isNotEmpty()) {
            val nc = "00000001"
            val bytes = ByteArray(8).also { random.nextBytes(it) }
            val cnonce = bytes.joinToString("") { "%02x".format(it) }
            response = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            // qop and nc unquoted per RFC 2617; cnonce quoted. Order mirrors curl --digest.
            parts += """cnonce="$cnonce""""
            parts += "nc=$nc"
            parts += "qop=$qop"
        } else {
            response = md5("$ha1:$nonce:$ha2")
        }
        parts += """response="$response""""
        if (opaque.isNotEmpty()) parts += """opaque="$opaque""""
        return "Digest " + parts.joinToString(", ")
    }

    private class Reply(val code: Int, val body: String, val challenge: String)

    private fun request(url: URL, authorization: String?): Reply {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            authorization?.let { setRequestProperty("Authorization", it) }
        }
        return try {
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText()?.trim().orEmpty()
            } catch (e: Exception) {
                ""
            }
            Reply(code, body, conn.getHeaderField("WWW-Authenticate").orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Calls [onResult] on the main thread. The second argument is an untranslated technical
     * detail (HTTP status and body, or an exception name) for the caller to wrap in a
     * localized message — it is empty on success.
     */
    fun open(unlock: Unlock, onResult: (Boolean, String) -> Unit) {
        io.execute {
            val (ok, message) = try {
                val url = URL(unlock.url)
                val target = url.path + if (url.query.isNullOrEmpty()) "" else "?${url.query}"

                // Unauthenticated first, to collect realm/nonce from the 401.
                var reply = request(url, null)
                if (reply.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    require(reply.challenge.isNotEmpty()) { "401 without a WWW-Authenticate header" }
                    val auth = authHeader(reply.challenge, "GET", target, unlock.user, unlock.password)
                    reply = request(url, auth)
                }
                Log.i(TAG, "unlock ${url.host} -> ${reply.code} ${reply.body.take(120)}")

                // Dahua answers a bare "OK" on success and "Error"/"Invalid Authority!" otherwise.
                val good = reply.code in 200..299 && !reply.body.contains("Error", ignoreCase = true)
                good to if (good) "" else "HTTP ${reply.code} ${reply.body.take(60)}"
            } catch (e: Exception) {
                Log.e(TAG, "unlock failed", e)
                false to "${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"
            }
            main.post { onResult(ok, message) }
        }
    }
}
