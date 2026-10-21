package com.nizar.geostamp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoStore
import com.nizar.geostamp.data.StampFields
import com.nizar.geostamp.render.Stamper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var recStatus: TextView
    private lateinit var customText: EditText
    private lateinit var shutter: Button
    private lateinit var modeBtn: Button
    private lateinit var flashBtn: Button
    private lateinit var locationManager: LocationManager

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateGpsStatus()
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    // Camera state
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false

    // Location state
    private var location: Location? = null
    private var address: String = ""

    private val locationListener = LocationListener { loc ->
        location = loc
        resolveAddress(loc)
        updateGpsStatus()
        updateInfo()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        gpsStatus = findViewById(R.id.gpsStatus)
        recStatus = findViewById(R.id.recStatus)
        customText = findViewById(R.id.customText)
        shutter = findViewById(R.id.shutter)
        modeBtn = findViewById(R.id.modeBtn)
        flashBtn = findViewById(R.id.flash)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val prefs = getSharedPreferences("geostamp", MODE_PRIVATE)
        customText.setText(prefs.getString("text", ""))
        customText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("text", s?.toString() ?: "").apply()
                updateInfo()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        shutter.setOnClickListener { if (videoMode) toggleRecording() else takePhoto() }
        findViewById<Button>(R.id.gallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<Button>(R.id.flip).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        flashBtn.setOnClickListener { cycleFlash() }
        modeBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            videoMode = !videoMode
            bindCamera()
        }

        // Pinch to zoom, tap to focus.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val state = cam.cameraInfo.zoomState.value ?: return true
                val ratio = (state.zoomRatio * detector.scaleFactor)
                    .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                return true
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            tapDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        updateLabels()

        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (hasCameraAndLocation()) {
            startAll()
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 11)
            }
        } else {
            ActivityCompat.requestPermissions(this, needed, 10)
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraAndLocation() =
        hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != 10) return
        if (hasCameraAndLocation()) {
            startAll()
        } else {
            Toast.makeText(this, "Camera and location permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    private fun startAll() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
        startLocation()
    }

    // ---------------------------------------------------------------- camera

    private fun bindCamera() {
        val p = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        torchOn = false
        p.unbindAll()
        try {
            if (videoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc
                imageCapture = null
                camera = p.bindToLifecycle(this, selector, preview, vc)
            } else {
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .build()
                imageCapture = ic
                videoCapture = null
                camera = p.bindToLifecycle(this, selector, preview, ic)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Camera not available: ${e.message}", Toast.LENGTH_LONG).show()
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                lensFacing = CameraSelector.LENS_FACING_BACK
                bindCamera()
            }
        }
        updateLabels()
    }

    private fun cycleFlash() {
        if (videoMode) {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
        }
        updateLabels()
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    private fun updateLabels() {
        modeBtn.text = if (videoMode) "Video" else "Photo"
        flashBtn.text = if (videoMode) {
            if (torchOn) "Torch on" else "Torch off"
        } else {
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "Flash auto"
                ImageCapture.FLASH_MODE_ON -> "Flash on"
                else -> "Flash off"
            }
        }
        shutter.text = when {
            !videoMode -> "Take photo"
            recording != null -> "Stop"
            else -> "Record"
        }
    }

    // -------------------------------------------------------------- location

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locationManager.isProviderEnabled(p)) {
                locationManager.getLastKnownLocation(p)?.let {
                    if (location == null) { location = it; resolveAddress(it) }
                }
                locationManager.requestLocationUpdates(p, 2000L, 1f, locationListener)
            }
        }
        updateGpsStatus()
        updateInfo()
    }

    private fun resolveAddress(loc: Location) {
        thread {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                val a = list?.firstOrNull()?.getAddressLine(0)
                if (a != null) { address = a; runOnUiThread { updateInfo() } }
            } catch (_: Exception) {
            }
        }
    }

    private fun updateGpsStatus() {
        val l = location
        if (l == null) {
            gpsStatus.text = "GPS: searching..."
            gpsStatus.setTextColor(Color.parseColor("#FF6B6B"))
            return
        }
        val age = (System.currentTimeMillis() - l.time) / 1000
        val acc = l.accuracy.toInt()
        gpsStatus.text = "GPS ±$acc m · ${age}s ago"
        gpsStatus.setTextColor(
            when {
                acc <= 15 && age < 60 -> Color.parseColor("#6BFF8A")
                acc <= 50 -> Color.parseColor("#FFD36B")
                else -> Color.parseColor("#FF6B6B")
            }
        )
    }

    private fun currentFields(time: Date): StampFields {
        val l = location
        return StampFields(
            text = customText.text.toString().trim(),
            date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(time),
            gps = if (l != null) String.format(Locale.US, "GPS: %.6f, %.6f", l.latitude, l.longitude)
                  else "GPS: waiting for fix...",
            address = address
        )
    }

    private fun updateInfo() {
        info.text = currentFields(Date()).lines().joinToString("\n")
    }

    // ----------------------------------------------------------------- photo

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (location == null) {
            Toast.makeText(this, "No GPS fix yet - the stamp will say \"waiting for fix\"", Toast.LENGTH_SHORT).show()
        }
        val fields = currentFields(Date())
        capture.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    thread {
                        val clean = Stamper.rotate(raw, rotation)
                        val key = PhotoStore.newKey()
                        PhotoStore.saveOriginal(this@MainActivity, key, clean)
                        PhotoStore.saveResult(this@MainActivity, key, clean, fields)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Saved (app gallery + Pictures/GeoStamp)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // ----------------------------------------------------------------- video

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        val vc = videoCapture ?: return
        val fields = currentFields(Date())  // the stamp is fixed at the moment recording starts
        val raw = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(raw).build())
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recStatus.visibility = android.view.View.VISIBLE
        recStatus.text = "REC 00:00"
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Status) {
                val s = event.recordingStats.recordedDurationNanos / 1_000_000_000
                recStatus.text = String.format(Locale.US, "REC %02d:%02d", s / 60, s % 60)
            } else if (event is VideoRecordEvent.Finalize) {
                recording = null
                updateLabels()
                if (event.hasError()) {
                    recStatus.visibility = android.view.View.GONE
                    Toast.makeText(this, "Recording failed (code ${event.error})", Toast.LENGTH_LONG).show()
                    raw.delete()
                } else {
                    watermarkVideo(raw, fields)
                }
            }
        }
        updateLabels()
    }

    /** Burns the watermark into the recorded video, then saves it to Movies/GeoStamp. */
    @OptIn(UnstableApi::class)
    private fun watermarkVideo(raw: File, fields: StampFields) {
        recStatus.visibility = android.view.View.VISIBLE
        recStatus.text = "Adding watermark..."

        var w = 0
        var h = 0
        var rot = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(raw.absolutePath)
            w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
        if (w <= 0 || h <= 0) {
            finishVideo(raw, null, "Could not read the video size")
            return
        }
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }  // size as shown on screen

        val out = File(cacheDir, "wm_${System.currentTimeMillis()}.mp4")
        try {
            val overlay = BitmapOverlay.createStaticBitmapOverlay(Stamper.overlayBitmap(w, h, fields.lines()))
            val effects = Effects(
                emptyList<AudioProcessor>(),
                listOf<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(raw)))
                .setEffects(effects)
                .build()
            val transformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finishVideo(raw, out, null)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        finishVideo(raw, null, "Watermark failed: ${exportException.errorCodeName}")
                    }
                })
                .build()
            transformer.start(item, out.absolutePath)
        } catch (e: Exception) {
            finishVideo(raw, null, "Watermark failed: ${e.message}")
        }
    }

    private fun finishVideo(raw: File, stamped: File?, error: String?) {
        if (stamped == null) {
            recStatus.visibility = android.view.View.GONE
            raw.delete()
            Toast.makeText(this, error ?: "Video failed", Toast.LENGTH_LONG).show()
            return
        }
        thread {
            val name = "GeoStamp_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val ok = Stamper.saveVideoToGallery(this, stamped, name)
            raw.delete()
            stamped.delete()
            runOnUiThread {
                recStatus.visibility = android.view.View.GONE
                Toast.makeText(
                    this,
                    if (ok) "Video saved to Movies/GeoStamp" else "Saving the video failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
