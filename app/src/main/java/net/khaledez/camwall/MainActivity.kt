package net.khaledez.camwall

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
 * The camera wall: one tile per camera, each with its own ExoPlayer. Remote-control
 * behaviour is described in the README.
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
        const val STALL_MS = 15_000L
        const val HEARTBEAT_MS = 60_000L
        const val CONFIG_RETRY_MS = 30_000L
        const val DOUBLE_PRESS_MS = 450L
    }

    private inner class Tile(val camera: Camera) {
        val texture = TextureView(this@MainActivity)
        val label = TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(14, 6, 14, 6)
            textSize = 12f
        }
        val cell = FrameLayout(this@MainActivity).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = false
            addView(texture, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START))
            setOnFocusChangeListener { _, _ -> paintBorder(this@Tile) }
        }
        var row = 0
        var col = 0

        var player: ExoPlayer? = null
        var muted = !camera.audio
        var failures = 0
        /** Why the stream is down, shown on the label until it plays again. */
        var error: String? = null
        val retry = Runnable { start() }

        /**
         * Media3's RTSP stack can stall without ever raising an error: a server that never
         * answers, or an RTP data gap that ends the stream silently. The session is not torn
         * down either, so a stalled tile both freezes and keeps queueing packets nothing
         * reads — the heap fills until the process dies. Any stall that outlasts STALL_MS
         * is treated as a failure, which releases the player and frees all of that.
         */
        val stallCheck = Runnable { fail("STALLED") }

        init {
            renderLabel()
        }

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
            // Set before prepare(): prepare() delivers the first state change synchronously,
            // and the listener ignores players that are not this tile's current one.
            player = p
            applyMute()
            p.setVideoTextureView(texture)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // release() reports its own timeout through here; a player we already
                    // dropped must not schedule a reconnect.
                    if (p !== player) return
                    Log.e(TAG, "${camera.name}: ${error.errorCodeName}", error)
                    fail(error.errorCodeName)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (p !== player) return
                    when (state) {
                        Player.STATE_READY -> {
                            handler.removeCallbacks(stallCheck)
                            failures = 0
                            error = null
                            renderLabel()
                        }
                        Player.STATE_BUFFERING -> {
                            handler.removeCallbacks(stallCheck)
                            handler.postDelayed(stallCheck, STALL_MS)
                        }
                        Player.STATE_ENDED -> fail("ENDED")
                        // Reached after an error, which onPlayerError already handles.
                        Player.STATE_IDLE -> Unit
                    }
                }
            })

            // Interleaved RTP over the RTSP TCP connection; see Camera.tcp for the trade-off.
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(camera.tcp)
                .setTimeoutMs(10_000)
                .createMediaSource(MediaItem.fromUri(camera.url))

            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = true
        }

        fun fail(reason: String) {
            handler.removeCallbacks(stallCheck)
            error = reason
            renderLabel()
            failures++
            val delay = minOf(RETRY_BASE_MS * failures, RETRY_MAX_MS)
            Log.i(TAG, "${camera.name}: $reason, reconnecting in ${delay}ms (attempt $failures)")
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, delay)
        }

        fun renderLabel() {
            val reason = error
            var text = if (reason == null) camera.name
                       else getString(R.string.label_error, camera.name, reason)
            if (!muted) text += "  ♪"
            if (this === movingTile) text += "  " + getString(R.string.label_moving)
            label.text = text
        }

        fun toggleMute(): Boolean {
            muted = !muted
            applyMute()
            renderLabel()
            return muted
        }

        private fun applyMute() {
            player?.volume = if (muted) 0f else 1f
        }

        fun stop() {
            val p = player
            player = null
            p?.release()
            handler.removeCallbacks(retry)
            handler.removeCallbacks(stallCheck)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tiles = ArrayList<Tile>()
    private var rows = 1
    private var cols = 1
    private var fullscreenTile: Tile? = null
    private var movingTile: Tile? = null
    private var lastCenterUp = 0L
    private var longPressFired = false

    private val reloadConfig = Runnable { recreate() }

    private val heartbeat = object : Runnable {
        override fun run() {
            CrashRestart.keepAlive()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashRestart.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemUi()
        val root = findViewById<FrameLayout>(R.id.root)

        val cameras = orderedCameras(CameraConfig.load(this))
        if (cameras.isEmpty()) {
            // Say so on screen rather than show cameras that can never connect. Storage may
            // not be mounted yet right after boot, so onStart tries again shortly.
            root.addView(TextView(this).apply {
                text = getString(R.string.label_no_cameras)
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }

        cols = ceil(sqrt(cameras.size.toDouble())).toInt()
        rows = ceil(cameras.size / cols.toDouble()).toInt()
        val grid = GridLayout(this).apply {
            columnCount = cols
            rowCount = rows
            setBackgroundColor(Color.BLACK)
        }
        cameras.forEachIndexed { i, cam ->
            val tile = Tile(cam)
            tile.row = i / cols
            tile.col = i % cols
            val params = GridLayout.LayoutParams(
                GridLayout.spec(tile.row, 1, 1f), GridLayout.spec(tile.col, 1, 1f)
            ).apply {
                width = 0
                height = 0
                setMargins(2, 2, 2, 2)
            }
            grid.addView(tile.cell, params)
            tiles.add(tile)
        }
        root.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tiles.first().cell.requestFocus()
        Log.i(TAG, "wall built: ${cameras.size} cameras in ${rows}x$cols")
    }

    /** Green for the selected tile, amber for the one being moved. */
    private fun paintBorder(tile: Tile) {
        val isMoving = tile === movingTile
        val isSelected = tile.cell.hasFocus()
        tile.cell.foreground = when {
            isMoving -> border(width = 6, color = "#FFB300")
            isSelected -> border(width = 4, color = "#3DDC84")
            else -> null
        }
    }

    private fun border(width: Int, color: String) = GradientDrawable().apply {
        setStroke(width, Color.parseColor(color))
        setColor(Color.TRANSPARENT)
    }

    // ---- tile order --------------------------------------------------------

    private fun prefs() = getSharedPreferences("layout", Context.MODE_PRIVATE)

    /** Saved order is a list of camera names; anything unknown falls to the end. */
    private fun orderedCameras(cameras: List<Camera>): List<Camera> {
        val saved = prefs().getString("order", null)?.split("\n")?.filter { it.isNotEmpty() }
            ?: return cameras
        return cameras.sortedBy { c ->
            saved.indexOf(c.name).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    private fun saveOrder() {
        val order = tiles.sortedWith(compareBy({ it.row }, { it.col })).map { it.camera.name }
        prefs().edit().putString("order", order.joinToString("\n")).apply()
        Log.i(TAG, "layout saved: ${order.joinToString(", ")}")
    }

    private fun applyPosition(tile: Tile) {
        val lp = tile.cell.layoutParams as GridLayout.LayoutParams
        lp.rowSpec = GridLayout.spec(tile.row, 1, 1f)
        lp.columnSpec = GridLayout.spec(tile.col, 1, 1f)
        tile.cell.layoutParams = lp
    }

    // ---- move mode ---------------------------------------------------------

    private fun startMove(tile: Tile) {
        movingTile = tile
        paintBorder(tile)
        tile.renderLabel()
        toast(getString(R.string.toast_move_hint, tile.camera.name))
    }

    private fun endMove() {
        val tile = movingTile ?: return
        movingTile = null
        paintBorder(tile)
        tile.renderLabel()
        saveOrder()
    }

    /** Swaps the moving tile with whatever occupies the neighbouring cell. */
    private fun moveBy(dRow: Int, dCol: Int): Boolean {
        val tile = movingTile ?: return false
        val targetRow = tile.row + dRow
        val targetCol = tile.col + dCol
        if (targetRow !in 0 until rows || targetCol !in 0 until cols) return true
        val other = tiles.firstOrNull { it.row == targetRow && it.col == targetCol } ?: return true

        val r = tile.row; val c = tile.col
        tile.row = other.row; tile.col = other.col
        other.row = r; other.col = c
        applyPosition(tile)
        applyPosition(other)
        // Layout runs after this pass; keep the selection on the tile being moved.
        tile.cell.post { tile.cell.requestFocus() }
        return true
    }

    private fun focusedTile(): Tile? = tiles.firstOrNull { it.cell.hasFocus() }

    // ---- full screen -------------------------------------------------------

    private fun toggleFullscreen(tile: Tile) {
        if (fullscreenTile === tile) exitFullscreen() else enterFullscreen(tile)
    }

    private fun enterFullscreen(tile: Tile) {
        fullscreenTile = tile
        tiles.forEach { it.cell.visibility = if (it === tile) View.VISIBLE else View.GONE }
        val lp = tile.cell.layoutParams as GridLayout.LayoutParams
        lp.rowSpec = GridLayout.spec(0, rows, 1f)
        lp.columnSpec = GridLayout.spec(0, cols, 1f)
        tile.cell.layoutParams = lp
        tile.cell.requestFocus()
        Log.i(TAG, "fullscreen: ${tile.camera.name}")
    }

    private fun exitFullscreen() {
        val tile = fullscreenTile ?: return
        fullscreenTile = null
        applyPosition(tile)
        tiles.forEach { it.cell.visibility = View.VISIBLE }
        tile.cell.requestFocus()
        Log.i(TAG, "back to grid")
    }

    // ---- actions -----------------------------------------------------------

    private fun showActions(tile: Tile) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        val isFullscreen = fullscreenTile === tile
        labels.add(getString(
            if (isFullscreen) R.string.action_back_to_grid else R.string.action_fullscreen))
        actions.add { toggleFullscreen(tile) }

        labels.add(getString(if (tile.muted) R.string.action_unmute else R.string.action_mute))
        actions.add {
            val muted = tile.toggleMute()
            toast(getString(
                if (muted) R.string.toast_muted else R.string.toast_unmuted, tile.camera.name))
        }

        tile.camera.unlock?.let { unlock ->
            labels.add(getString(R.string.action_open_lock))
            actions.add { confirmUnlock(tile, unlock) }
        }

        labels.add(getString(R.string.action_move))
        actions.add { startMove(tile) }

        labels.add(getString(R.string.action_exit))
        actions.add { finish() }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tile.camera.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** A lock is physical and one-way: always confirm before releasing it. */
    private fun confirmUnlock(tile: Tile, unlock: Unlock) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.unlock_confirm_title)
            .setMessage(getString(R.string.unlock_confirm_message, tile.camera.name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_open) { _, _ ->
                toast(getString(R.string.toast_unlocking, tile.camera.name))
                Unlocker.open(unlock) { ok, detail ->
                    toast(if (ok) getString(R.string.toast_unlock_ok)
                          else getString(R.string.toast_unlock_failed, detail))
                }
            }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- keys --------------------------------------------------------------

    private fun isCenter(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (movingTile != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> return moveBy(-1, 0)
                KeyEvent.KEYCODE_DPAD_DOWN -> return moveBy(1, 0)
                KeyEvent.KEYCODE_DPAD_LEFT -> return moveBy(0, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return moveBy(0, 1)
            }
        }
        if (!isCenter(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            event.startTracking()
            longPressFired = false
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isCenter(keyCode)) return super.onKeyLongPress(keyCode, event)
        focusedTile()?.let { showActions(it) }
        longPressFired = true
        return true
    }

    /** OK confirms a move, ends a long press, or — pressed twice — toggles full screen. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isCenter(keyCode)) return super.onKeyUp(keyCode, event)
        if (movingTile != null) {
            endMove()
            longPressFired = false
            lastCenterUp = 0L
            return true
        }
        if (longPressFired) {
            longPressFired = false
            return true
        }
        val now = System.currentTimeMillis()
        val isDoublePress = now - lastCenterUp <= DOUBLE_PRESS_MS
        if (!isDoublePress) {
            lastCenterUp = now
            return true
        }
        lastCenterUp = 0L
        focusedTile()?.let { toggleFullscreen(it) }
        return true
    }

    /**
     * Full screen -> grid -> leave the app. The wall is started by BootReceiver and sits on
     * top of the Google TV launcher, so BACK must be able to get out: swallowing it strands
     * anyone whose remote has no working HOME button.
     */
    override fun onBackPressed() {
        when {
            movingTile != null -> endMove()
            fullscreenTile != null -> exitFullscreen()
            else -> finish()
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun onStart() {
        super.onStart()
        tiles.forEach { it.start() }
        if (tiles.isEmpty()) handler.postDelayed(reloadConfig, CONFIG_RETRY_MS)
        heartbeat.run()
    }

    override fun onStop() {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(reloadConfig)
        CrashRestart.cancel()
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
