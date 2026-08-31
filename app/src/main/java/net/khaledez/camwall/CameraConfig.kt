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
    val unlock: Unlock? = null
)

/**
 * Cameras are read from cameras.json in the app's external files dir, so URLs (and the
 * credentials embedded in them) can be changed with `adb push` and never live in the APK:
 *
 *   adb push cameras.json /sdcard/Android/data/net.khaledez.camwall/files/cameras.json
 *
 * Falls back to DEFAULTS when the file is missing or unparseable.
 */
object CameraConfig {
    private const val TAG = "CamWall"
    const val FILE_NAME = "cameras.json"

    private val DEFAULTS = listOf(
        // Dahua VTO door stations: main stream, H.264 1280x720. The substream
        // (subtype=1) is only 352x288, too soft for a wall tile.
        Camera("Cam 200", "rtsp://USER:PASS@192.168.88.200:554/cam/realmonitor?channel=1&subtype=0"),
        Camera("Cam 202", "rtsp://USER:PASS@192.168.88.202:554/cam/realmonitor?channel=1&subtype=0"),
        // Tiandy TC-C382V: path is /<channel>/<stream>. /1/2 is H.265 1408x528;
        // /1/1 is the 4640x1728 panoramic main stream, too big for a tile.
        Camera("Cam 10", "rtsp://USER:PASS@192.168.88.10:554/1/2"),
        Camera("Cam 15", "rtsp://USER:PASS@192.168.88.15:554/1/2")
    )

    fun load(context: Context): List<Camera> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.canRead()) {
            Log.w(TAG, "no ${file.absolutePath}, using built-in defaults")
            return DEFAULTS
        }
        return try {
            val arr = JSONObject(file.readText()).getJSONArray("cameras")
            val out = ArrayList<Camera>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optJSONObject("unlock")?.let {
                    Unlock(it.getString("url"), it.optString("user"), it.optString("pass"))
                }
                out.add(
                    Camera(
                        name = o.optString("name", "Cam ${i + 1}"),
                        url = o.getString("url"),
                        audio = o.optBoolean("audio", false),
                        unlock = u
                    )
                )
            }
            if (out.isEmpty()) DEFAULTS else out
        } catch (e: Exception) {
            Log.e(TAG, "bad ${file.absolutePath}, using defaults", e)
            DEFAULTS
        }
    }
}
