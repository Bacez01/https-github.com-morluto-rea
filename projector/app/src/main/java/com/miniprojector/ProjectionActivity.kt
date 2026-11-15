package com.miniprojector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Full-screen projection: the picture is mirrored for the lens, the screen
 * is at full brightness and never goes to sleep. Tap to show the controls,
 * pinch to zoom, drag to move the picture, double tap to recentre it.
 */
class ProjectionActivity : Activity() {

    private lateinit var settings: ProjectorSettings
    private lateinit var stage: FrameLayout
    private lateinit var image: ImageView
    private lateinit var video: TextureView
    private lateinit var controls: View
    private lateinit var playPause: Button
    private lateinit var keystoneLabel: TextView
    private var pattern: FocusPatternView? = null

    private var player: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var resumePosition = 0
    private var playWhenReady = true

    private var panX = 0f
    private var panY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControlsVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection)
        settings = ProjectorSettings(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }

        stage = findViewById(R.id.stage)
        image = findViewById(R.id.image)
        video = findViewById(R.id.video)
        controls = findViewById(R.id.controls)
        playPause = findViewById(R.id.play_pause)
        keystoneLabel = findViewById(R.id.keystone_label)

        stage.cameraDistance = 12000 * resources.displayMetrics.density

        savedInstanceState?.let {
            resumePosition = it.getInt(STATE_POSITION)
            playWhenReady = it.getBoolean(STATE_PLAYING, true)
            panX = it.getFloat(STATE_PAN_X)
            panY = it.getFloat(STATE_PAN_Y)
        }

        setUpControls()
        setUpGestures()
        openContent(intent)
        applyTransform()
        setControlsVisible(true)
    }

    // ---- Content -------------------------------------------------------------------------

    private fun openContent(intent: Intent) {
        val uri = mediaUri(intent)
        if (uri == null || intent.getBooleanExtra(EXTRA_PATTERN, false)) {
            showPattern(true)
            return
        }
        val type = intent.type ?: contentResolver.getType(uri)
        if (type?.startsWith("video/") == true) showVideo(uri) else showImage(uri)
    }

    private fun mediaUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        else -> intent.data
    }

    private fun showImage(uri: Uri) {
        image.visibility = View.VISIBLE
        playPause.visibility = View.GONE
        val metrics = resources.displayMetrics
        thread {
            val bitmap = runCatching { decodeImage(uri, metrics.widthPixels, metrics.heightPixels) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bitmap == null) failed() else image.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeImage(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also follows the photo's EXIF orientation.
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    ProjectionMath.sampleSize(info.size.width, info.size.height, maxWidth, maxHeight)
                )
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = ProjectionMath.sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val degrees = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun showVideo(uri: Uri) {
        video.visibility = View.VISIBLE
        playPause.visibility = View.VISIBLE
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                startPlayer(uri, Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitVideo()

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
    }

    private fun startPlayer(uri: Uri, surface: Surface) {
        videoSurface = surface
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(this, uri)
        } catch (e: Exception) {
            failed()
            return
        }
        mp.setSurface(surface)
        mp.isLooping = true
        mp.setOnVideoSizeChangedListener { _, _, _ -> fitVideo() }
        mp.setOnErrorListener { _, _, _ -> failed(); true }
        mp.setOnPreparedListener {
            fitVideo()
            if (resumePosition > 0) it.seekTo(resumePosition)
            if (playWhenReady) it.start()
            updatePlayPause()
        }
        mp.prepareAsync()
    }

    private fun fitVideo() {
        val mp = player ?: return
        val (sx, sy) = runCatching {
            ProjectionMath.videoFitScale(mp.videoWidth, mp.videoHeight, video.width, video.height)
        }.getOrDefault(1f to 1f)
        video.setTransform(Matrix().apply { setScale(sx, sy, video.width / 2f, video.height / 2f) })
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { resumePosition = it.currentPosition }
            it.release()
        }
        player = null
        videoSurface?.release()
        videoSurface = null
    }

    private fun showPattern(show: Boolean) {
        if (show && pattern == null) {
            pattern = FocusPatternView(this).also {
                stage.addView(it, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        } else if (!show) {
            pattern?.let { stage.removeView(it) }
            pattern = null
        }
    }

    private fun failed() {
        Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    // ---- Mirror, zoom, keystone ----------------------------------------------------------

    private fun applyTransform() {
        val t = ProjectionMath.stageTransform(
            settings.flipHorizontal, settings.flipVertical, settings.zoom, panX, panY)
        stage.scaleX = t.scaleX
        stage.scaleY = t.scaleY
        stage.translationX = t.translationX
        stage.translationY = t.translationY
        stage.rotationX = settings.keystone
    }

    private fun setZoom(zoom: Float) {
        settings.zoom = ProjectionMath.clampZoom(zoom)
        applyTransform()
    }

    private fun resetPosition() {
        panX = 0f
        panY = 0f
        settings.zoom = 1f
        applyTransform()
    }

    // ---- Controls ------------------------------------------------------------------------

    private fun setUpControls() {
        playPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.start() }
            updatePlayPause()
            bumpControls()
        }
        findViewById<Button>(R.id.flip_v).setOnClickListener {
            settings.flipVertical = !settings.flipVertical
            applyTransform(); bumpControls()
        }
        findViewById<Button>(R.id.flip_h).setOnClickListener {
            settings.flipHorizontal = !settings.flipHorizontal
            applyTransform(); bumpControls()
        }
        findViewById<Button>(R.id.zoom_out).setOnClickListener { setZoom(settings.zoom / 1.1f); bumpControls() }
        findViewById<Button>(R.id.zoom_in).setOnClickListener { setZoom(settings.zoom * 1.1f); bumpControls() }
        findViewById<Button>(R.id.pattern).setOnClickListener {
            // The chart sits above the photo or video; turning it off brings them back.
            if (image.visibility == View.VISIBLE || video.visibility == View.VISIBLE) showPattern(pattern == null)
            bumpControls()
        }
        findViewById<Button>(R.id.reset).setOnClickListener {
            resetPosition()
            settings.keystone = 0f
            keystoneBar.progress = keystoneToProgress(0f)
            applyTransform(); bumpControls()
        }
        findViewById<Button>(R.id.close).setOnClickListener { finish() }

        keystoneBar.max = (ProjectionMath.MAX_KEYSTONE * 2).toInt()
        keystoneBar.progress = keystoneToProgress(settings.keystone)
        updateKeystoneLabel()
        keystoneBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.keystone = ProjectionMath.clampKeystone(progress - ProjectionMath.MAX_KEYSTONE)
                updateKeystoneLabel()
                applyTransform()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = handler.removeCallbacks(hideControls)
            override fun onStopTrackingTouch(bar: SeekBar) = bumpControls()
        })
    }

    private val keystoneBar: SeekBar by lazy { findViewById(R.id.keystone) }

    private fun keystoneToProgress(degrees: Float) = (degrees + ProjectionMath.MAX_KEYSTONE).toInt()

    private fun updateKeystoneLabel() {
        keystoneLabel.text = getString(R.string.keystone_value, settings.keystone.toInt())
    }

    private fun updatePlayPause() {
        playPause.setText(if (player?.isPlaying == true) R.string.pause else R.string.play)
    }

    private fun setControlsVisible(visible: Boolean) {
        handler.removeCallbacks(hideControls)
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    private fun bumpControls() = setControlsVisible(true)

    private fun setUpGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(settings.zoom * detector.scaleFactor)
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(controls.visibility != View.VISIBLE)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetPosition()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale.isInProgress) return false
                panX -= dx
                panY -= dy
                applyTransform()
                return true
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
    }

    // ---- Full screen ---------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---- Lifecycle -----------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        player?.let {
            playWhenReady = it.isPlaying
            resumePosition = it.currentPosition
            it.pause()
            updatePlayPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (playWhenReady) player?.start()
        if (player != null) updatePlayPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { runCatching { resumePosition = it.currentPosition } }
        outState.putInt(STATE_POSITION, resumePosition)
        outState.putBoolean(STATE_PLAYING, playWhenReady)
        outState.putFloat(STATE_PAN_X, panX)
        outState.putFloat(STATE_PAN_Y, panY)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideControls)
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATTERN = "pattern"
        private const val CONTROLS_TIMEOUT_MS = 4000L
        private const val STATE_POSITION = "position"
        private const val STATE_PLAYING = "playing"
        private const val STATE_PAN_X = "pan_x"
        private const val STATE_PAN_Y = "pan_y"

        fun media(context: Context, uri: Uri, isVideo: Boolean): Intent =
            Intent(context, ProjectionActivity::class.java)
                .setDataAndType(uri, if (isVideo) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun focusPattern(context: Context): Intent =
            Intent(context, ProjectionActivity::class.java).putExtra(EXTRA_PATTERN, true)
    }
}
