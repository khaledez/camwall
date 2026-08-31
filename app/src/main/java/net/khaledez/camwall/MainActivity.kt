package net.khaledez.camwall

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Remote control:
 *   D-pad            move the selection between tiles
 *   OK, twice        full screen the selected tile
 *   BACK             leave full screen (ignored in the grid — this is the HOME app)
 *   OK, long press   actions for the selected tile (mute, unlock)
 *
 * Video goes to TextureViews, not SurfaceViews. SurfaceView asks for a hardware overlay
 * plane, and this SoC's compositor mis-assigns buffers when four video planes are live —
 * two tiles end up showing the same camera. TextureView composites on the GPU, which is
 * correct, and at 2x720p + 2x1408x528 the cost is not measurable here.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private companion object {
        const val TAG = "CamWall"
        const val RETRY_BASE_MS = 2_000L
        const val RETRY_MAX_MS = 30_000L
        const val DOUBLE_PRESS_MS = 450L
    }

    private inner class Tile(val camera: Camera, val index: Int) {
        lateinit var cell: FrameLayout
        lateinit var texture: TextureView
        lateinit var label: TextView
        lateinit var rowSpec: GridLayout.Spec
        lateinit var colSpec: GridLayout.Spec

        var player: ExoPlayer? = null
        var muted = !camera.audio
        var failures = 0
        val retry = Runnable { start() }

        fun start() {
            stop()
            val p = ExoPlayer.Builder(this@MainActivity)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        // Small buffers: this is live video, latency matters more than smoothing.
                        .setBufferDurationsMs(1_000, 5_000, 500, 1_000)
                        .build()
                )
                .build()

            p.setVideoTextureView(texture)
            p.volume = if (muted) 0f else 1f
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "${camera.name}: ${error.errorCodeName}", error)
                    setLabel("${camera.name} — ${error.errorCodeName}")
                    scheduleRetry()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        failures = 0
                        setLabel(camera.name)
                    }
                }
            })

            // forceUseRtpTcp: interleaved RTP over the RTSP TCP connection. Cameras that
            // refuse UDP, and anything crossing a NAT, need this.
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(10_000)
                .createMediaSource(MediaItem.fromUri(camera.url))

            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = true
            player = p
        }

        fun setLabel(text: String) {
            label.text = if (muted) text else "$text  ♪"
        }

        fun toggleMute(): Boolean {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            setLabel(camera.name)
            return muted
        }

        fun scheduleRetry() {
            failures++
            val delay = minOf(RETRY_BASE_MS * failures, RETRY_MAX_MS)
            Log.i(TAG, "${camera.name}: reconnecting in ${delay}ms (attempt $failures)")
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, delay)
        }

        fun stop() {
            handler.removeCallbacks(retry)
            player?.release()
            player = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tiles = ArrayList<Tile>()
    private lateinit var grid: GridLayout
    private var rows = 1
    private var cols = 1
    private var fullscreenIndex = -1
    private var lastCenterUp = 0L
    private var longPressFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemUi()

        val cameras = CameraConfig.load(this)
        cols = ceil(sqrt(cameras.size.toDouble())).toInt().coerceAtLeast(1)
        rows = ceil(cameras.size / cols.toDouble()).toInt().coerceAtLeast(1)

        grid = GridLayout(this).apply {
            columnCount = cols
            rowCount = rows
            setBackgroundColor(Color.BLACK)
        }

        cameras.forEachIndexed { i, cam ->
            val tile = Tile(cam, i)
            tile.texture = TextureView(this)
            tile.label = TextView(this).apply {
                text = cam.name
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(150, 0, 0, 0))
                setPadding(14, 6, 14, 6)
                textSize = 12f
            }
            tile.cell = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                isFocusable = true
                isFocusableInTouchMode = false
                addView(
                    tile.texture,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    tile.label,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START
                    )
                )
                setOnFocusChangeListener { v, hasFocus ->
                    v.foreground = if (hasFocus) selectionBorder() else null
                }
            }

            tile.rowSpec = GridLayout.spec(i / cols, 1, 1f)
            tile.colSpec = GridLayout.spec(i % cols, 1, 1f)
            val params = GridLayout.LayoutParams(tile.rowSpec, tile.colSpec).apply {
                width = 0
                height = 0
                setMargins(2, 2, 2, 2)
            }
            grid.addView(tile.cell, params)
            tiles.add(tile)
        }

        findViewById<FrameLayout>(R.id.root).addView(
            grid,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        tiles.firstOrNull()?.cell?.requestFocus()
        Log.i(TAG, "wall built: ${cameras.size} cameras in ${rows}x$cols")
    }

    private fun selectionBorder() = GradientDrawable().apply {
        setStroke(4, Color.parseColor("#3DDC84"))
        setColor(Color.TRANSPARENT)
    }

    private fun focusedTile(): Tile? = tiles.firstOrNull { it.cell.hasFocus() }

    // ---- full screen -------------------------------------------------------

    private fun enterFullscreen(tile: Tile) {
        fullscreenIndex = tile.index
        tiles.forEach { it.cell.visibility = if (it === tile) View.VISIBLE else View.GONE }
        val lp = tile.cell.layoutParams as GridLayout.LayoutParams
        lp.rowSpec = GridLayout.spec(0, rows, 1f)
        lp.columnSpec = GridLayout.spec(0, cols, 1f)
        tile.cell.layoutParams = lp
        tile.cell.requestFocus()
        Log.i(TAG, "fullscreen: ${tile.camera.name}")
    }

    private fun exitFullscreen() {
        val tile = tiles.getOrNull(fullscreenIndex) ?: return
        val lp = tile.cell.layoutParams as GridLayout.LayoutParams
        lp.rowSpec = tile.rowSpec
        lp.columnSpec = tile.colSpec
        tile.cell.layoutParams = lp
        tiles.forEach { it.cell.visibility = View.VISIBLE }
        fullscreenIndex = -1
        tile.cell.requestFocus()
        Log.i(TAG, "back to grid")
    }

    // ---- actions -----------------------------------------------------------

    private fun showActions(tile: Tile) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(if (fullscreenIndex == tile.index) "Back to grid" else "Full screen")
        actions.add { if (fullscreenIndex == tile.index) exitFullscreen() else enterFullscreen(tile) }

        labels.add(if (tile.muted) "Unmute" else "Mute")
        actions.add {
            val muted = tile.toggleMute()
            toast("${tile.camera.name}: ${if (muted) "muted" else "unmuted"}")
        }

        tile.camera.unlock?.let { unlock ->
            labels.add("Open lock")
            actions.add { confirmUnlock(tile, unlock) }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tile.camera.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** A lock is physical and one-way: always confirm before releasing it. */
    private fun confirmUnlock(tile: Tile, unlock: Unlock) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Open lock?")
            .setMessage("Release the door at ${tile.camera.name}.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open") { _, _ ->
                toast("Opening ${tile.camera.name}…")
                Unlocker.open(unlock) { _, message -> toast(message) }
            }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- keys --------------------------------------------------------------

    private fun isCenter(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenter(keyCode)) {
            if (event.repeatCount == 0) {
                event.startTracking()
                longPressFired = false
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenter(keyCode)) {
            focusedTile()?.let { showActions(it) }
            longPressFired = true
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenter(keyCode)) {
            if (longPressFired) {
                longPressFired = false
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastCenterUp <= DOUBLE_PRESS_MS) {
                lastCenterUp = 0L
                focusedTile()?.let {
                    if (fullscreenIndex == it.index) exitFullscreen() else enterFullscreen(it)
                }
            } else {
                lastCenterUp = now
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** In the grid there is nothing to go back to; this is the HOME activity. */
    override fun onBackPressed() {
        if (fullscreenIndex >= 0) exitFullscreen()
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun onStart() {
        super.onStart()
        tiles.forEach { it.start() }
    }

    override fun onStop() {
        tiles.forEach { it.stop() }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
    }
}
