package com.nizar.geostamp.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoStore
import com.nizar.geostamp.data.StampFields
import com.nizar.geostamp.data.VideoInfo
import com.nizar.geostamp.render.VideoWatermarker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Pick a video from the phone and burn a watermark into it. The date line starts at the time
 * you type (at the first frame) and, if "clock runs" is ticked, keeps counting until the video ends.
 */
class VideoEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_PICK = "auto_pick"
    }

    private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var uri: Uri? = null
    private var probe: VideoInfo.Probe? = null
    private var cancel: (() -> Unit)? = null

    private lateinit var preview: ImageView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fText: EditText
    private lateinit var fStart: EditText
    private lateinit var fGps: EditText
    private lateinit var fAddress: EditText
    private lateinit var running: CheckBox
    private lateinit var apply: Button

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) setVideo(picked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_edit)
        preview = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        fText = findViewById(R.id.fText)
        fStart = findViewById(R.id.fStart)
        fGps = findViewById(R.id.fGps)
        fAddress = findViewById(R.id.fAddress)
        running = findViewById(R.id.running)
        apply = findViewById(R.id.apply)

        fText.setText(getSharedPreferences("geostamp", MODE_PRIVATE).getString("text", ""))
        fStart.setText(dateFormat.format(Date()))

        findViewById<Button>(R.id.choose).setOnClickListener { launchPicker() }
        apply.setOnClickListener { applyWatermark() }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_PICK, false)) {
            launchPicker()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel?.invoke()
    }

    private fun launchPicker() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun setVideo(picked: Uri) {
        uri = picked
        probe = null
        info.text = "Reading video..."
        thread {
            val p = VideoInfo.probe(this, picked)
            val frame = VideoInfo.frame(this, picked)
            runOnUiThread {
                probe = p
                preview.setImageBitmap(frame)
                if (p == null) {
                    info.text = "Could not read this video"
                } else {
                    val s = p.durationMs / 1000
                    info.text = String.format(Locale.US, "%d x %d, %d:%02d", p.width, p.height, s / 60, s % 60)
                    // Suggest the video's own creation time as the start time.
                    p.createdMillis?.let { fStart.setText(dateFormat.format(Date(it))) }
                }
            }
        }
    }

    private fun applyWatermark() {
        val source = uri
        val p = probe
        if (source == null || p == null) {
            Toast.makeText(this, "Choose a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val format = dateFormat.apply { isLenient = false }
        val start = try {
            format.parse(fStart.text.toString().trim())
        } catch (_: Exception) {
            null
        }
        if (start == null) {
            Toast.makeText(this, "Start time must look like 2026-10-21 14:30:00", Toast.LENGTH_LONG).show()
            return
        }

        val fields = StampFields(
            text = fText.text.toString().trim(),
            date = dateFormat.format(start),
            gps = fGps.text.toString().trim(),
            address = fAddress.text.toString().trim()
        )
        val spec = VideoWatermarker.ClockSpec(
            fields.text, start.time, running.isChecked, fields.gps, fields.address
        )
        val out = File(cacheDir, "wm_${System.currentTimeMillis()}.mp4")

        apply.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Adding watermark... keep this screen open, long videos take a while."

        cancel = VideoWatermarker.start(this, source, out, p.width, p.height, spec) { error ->
            cancel = null
            if (error != null) {
                out.delete()
                progress.visibility = View.GONE
                apply.isEnabled = true
                status.text = error
                return@start
            }
            thread {
                PhotoStore.saveVideoResult(this, out, fields)
                runOnUiThread {
                    Toast.makeText(this, "Video saved (app gallery + Movies/GeoStamp)", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}
