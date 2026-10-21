package com.nizar.geostamp.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoImporter
import com.nizar.geostamp.data.PhotoStore
import com.nizar.geostamp.data.StampFields
import kotlin.concurrent.thread

/** Pick many photos from the phone and give them all the same watermark. */
class BatchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_PICK = "auto_pick"
    }

    private var uris: List<Uri> = emptyList()
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var fText: EditText
    private lateinit var fDate: EditText
    private lateinit var fGps: EditText
    private lateinit var fAddress: EditText
    private lateinit var apply: Button

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { list ->
        if (list.isNotEmpty()) setUris(list)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch)
        count = findViewById(R.id.count)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        fText = findViewById(R.id.fText)
        fDate = findViewById(R.id.fDate)
        fGps = findViewById(R.id.fGps)
        fAddress = findViewById(R.id.fAddress)
        apply = findViewById(R.id.apply)

        fText.setText(getSharedPreferences("geostamp", MODE_PRIVATE).getString("text", ""))

        findViewById<Button>(R.id.choose).setOnClickListener { picker.launch("image/*") }
        apply.setOnClickListener { applyToAll() }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_PICK, false)) {
            picker.launch("image/*")
        }
    }

    private fun setUris(list: List<Uri>) {
        uris = list
        count.text = "${list.size} photo(s) selected"
        thread {
            try {
                val b = PhotoImporter.decodeSampled(this, list[0], 800)
                runOnUiThread { preview.setImageBitmap(b) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyToAll() {
        if (uris.isEmpty()) {
            Toast.makeText(this, "Choose photos first", Toast.LENGTH_SHORT).show()
            return
        }
        val text = fText.text.toString().trim()
        val date = fDate.text.toString().trim()
        val gps = fGps.text.toString().trim()
        val address = fAddress.text.toString().trim()
        val list = uris
        apply.isEnabled = false

        thread {
            var ok = 0
            var failed = 0
            list.forEachIndexed { i, uri ->
                try {
                    val imp = PhotoImporter.read(this, uri)
                    // An empty date/GPS field means "use each photo's own value".
                    val fields = StampFields(
                        text = text,
                        date = date.ifEmpty { imp.date },
                        gps = gps.ifEmpty { imp.gps },
                        address = address
                    )
                    val key = PhotoStore.newKey()
                    PhotoStore.saveOriginal(this, key, imp.bitmap)
                    PhotoStore.saveResult(this, key, imp.bitmap, fields)
                    ok++
                } catch (_: Throwable) {
                    failed++
                }
                runOnUiThread { status.text = "Processed ${i + 1} / ${list.size}" }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Done: $ok saved" + if (failed > 0) ", $failed failed" else "",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }
}
