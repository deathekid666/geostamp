package com.nizar.geostamp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoStore
import com.nizar.geostamp.data.StampFields
import com.nizar.geostamp.render.Stamper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var customText: EditText
    private lateinit var locationManager: LocationManager

    private var imageCapture: ImageCapture? = null
    private var location: Location? = null
    private var address: String = ""

    private val locationListener = LocationListener { loc ->
        location = loc
        resolveAddress(loc)
        updateInfo()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        customText = findViewById(R.id.customText)
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

        findViewById<Button>(R.id.capture).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.gallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startAll()
        } else {
            ActivityCompat.requestPermissions(this, needed, 10)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startAll()
        } else {
            Toast.makeText(this, "Camera and location permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAll() {
        startCamera()
        startLocation()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

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
        updateInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
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

    private fun takePhoto() {
        val capture = imageCapture ?: return
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
}
