package com.nizar.geostamp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class EditActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var image: ImageView
    private lateinit var fText: EditText
    private lateinit var fDate: EditText
    private lateinit var fGps: EditText
    private lateinit var fAddress: EditText
    private var keys: List<String> = emptyList()
    private var current: Bitmap? = null
    private var currentKey: String = ""

    private val dir get() = File(filesDir, "originals").apply { mkdirs() }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        spinner = findViewById(R.id.photos)
        image = findViewById(R.id.image)
        fText = findViewById(R.id.fText)
        fDate = findViewById(R.id.fDate)
        fGps = findViewById(R.id.fGps)
        fAddress = findViewById(R.id.fAddress)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in keys.indices) load(keys[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.importPhoto).setOnClickListener { picker.launch("image/*") }
        findViewById<Button>(R.id.save).setOnClickListener { save() }

        refreshKeys(null)
    }

    private fun refreshKeys(select: String?) {
        keys = dir.listFiles { f -> f.extension == "jpg" }
            ?.map { it.nameWithoutExtension }?.sortedDescending() ?: emptyList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, keys)
        val idx = keys.indexOf(select).takeIf { it >= 0 } ?: 0
        if (keys.isNotEmpty()) spinner.setSelection(idx)
    }

    private fun load(key: String) {
        currentKey = key
        current = BitmapFactory.decodeFile(File(dir, "$key.jpg").absolutePath)
        image.setImageBitmap(current)
        val txt = File(dir, "$key.txt")
        val f = if (txt.exists()) txt.readText().split("\n") else emptyList()
        fun at(i: Int) = f.getOrElse(i) { "" }
        fText.setText(at(0))
        fDate.setText(at(1))
        fGps.setText(at(2))
        fAddress.setText(at(3))
    }

    /** Copies a photo from the gallery into the app (with its EXIF date/GPS prefilled when present). */
    private fun importPhoto(uri: Uri) {
        thread {
            try {
                val exif = contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                val orientation = exif?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                ) ?: ExifInterface.ORIENTATION_NORMAL
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }

                val outFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val date = try {
                    val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    outFmt.format(SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw!!)!!)
                } catch (_: Exception) {
                    outFmt.format(Date())
                }

                val ll = FloatArray(2)
                val gps = if (exif != null && exif.getLatLong(ll))
                    String.format(Locale.US, "GPS: %.6f, %.6f", ll[0], ll[1]) else ""

                // Decode with a size cap to avoid running out of memory on huge photos.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IllegalStateException("decode failed")
                val bmp = Stamper.rotate(decoded, degrees)

                val key = "import_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                File(dir, "$key.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                File(dir, "$key.txt").writeText(listOf("", date, gps, "").joinToString("\n"))
                runOnUiThread { refreshKeys(key) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not import photo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun save() {
        val bmp = current ?: run {
            Toast.makeText(this, "Import or choose a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = listOf(fText, fDate, fGps, fAddress)
            .map { it.text.toString().trim() }
            .filter { it.isNotEmpty() }
        val key = currentKey
        thread {
            val stamped = Stamper.draw(bmp, lines)
            val ok = Stamper.saveToGallery(this, stamped, "GeoStamp_edit_${key}_${System.currentTimeMillis()}.jpg")
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Saved as a new copy in Pictures/GeoStamp" else "Save failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
