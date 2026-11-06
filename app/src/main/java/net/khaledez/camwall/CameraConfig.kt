package net.khaledez.camwall

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** An HTTP endpoint that releases a door strike / gate relay. Digest or basic auth. */
data class Unlock(val url: String, val user: String, val password: String)

data class Camera(
    val name: String,
    val url: String,
    /** Start unmuted. Default false — four live audio streams at once is not useful. */
    val audio: Boolean = false,
    /**
     * Interleave RTP over the RTSP TCP connection. Default true: cameras that refuse UDP
     * need it. Set false to use UDP, which sidesteps Media3's interleaved-TCP parser (one
     * of the ways its RTSP stack fills the heap, see the README) at the cost of UDP's own
     * packet loss. Media3 falls back to TCP by itself if UDP gets no data at all.
     */
    val tcp: Boolean = true,
    val unlock: Unlock? = null
)

/**
 * Cameras are read from cameras.json in the app's external files dir, so URLs (and the
 * credentials embedded in them) can be changed with `adb push` and never live in the APK:
 *
 *   adb push cameras.json /sdcard/Android/data/net.khaledez.camwall/files/cameras.json
 *
 * There are no built-in cameras: a missing or broken file yields an empty list, which the
 * wall reports on screen. Entries that cannot be read are skipped one by one.
 */
object CameraConfig {
    private const val TAG = "CamWall"
    const val FILE_NAME = "cameras.json"

    fun load(context: Context): List<Camera> {
        val dir = context.getExternalFilesDir(null)
        val file = File(dir, FILE_NAME)
        if (dir == null || !file.canRead()) {
            Log.w(TAG, "no readable ${file.absolutePath}")
            return emptyList()
        }
        val arr = try {
            JSONObject(file.readText()).getJSONArray("cameras")
        } catch (e: Exception) {
            // Not the exception itself: JSONException messages quote the whole input,
            // which would put every password in the file into logcat.
            Log.e(TAG, "bad ${file.absolutePath}: ${e.javaClass.simpleName}")
            return emptyList()
        }

        val cameras = ArrayList<Camera>(arr.length())
        for (i in 0 until arr.length()) {
            try {
                cameras.add(parseCamera(arr.getJSONObject(i), i))
            } catch (e: Exception) {
                Log.e(TAG, "skipping camera #${i + 1} in ${FILE_NAME}: ${e.javaClass.simpleName}")
            }
        }
        return cameras
    }

    private fun parseCamera(o: JSONObject, index: Int): Camera {
        val unlock = o.optJSONObject("unlock")?.let {
            Unlock(it.getString("url"), it.optString("user"), it.optString("pass"))
        }
        return Camera(
            name = o.optString("name", "Cam ${index + 1}"),
            url = o.getString("url"),
            audio = o.optBoolean("audio", false),
            tcp = o.optBoolean("tcp", true),
            unlock = unlock
        )
    }
}
