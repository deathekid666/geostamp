package com.nizar.geostamp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

class EditActivity : AppCompatActivity() {

    private lateinit var image: ImageView
    private lateinit var fText: EditText
    private lateinit var fDate: EditText
    private lateinit var fGps: EditText
    private lateinit var fAddress: EditText
    private var keys: List<String> = emptyList()
    private var current: Bitmap? = null
    private var currentKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        image = findViewById(R.id.image)
        fText = findViewById(R.id.fText)
        fDate = findViewById(R.id.fDate)
        fGps = findViewById(R.id.fGps)
        fAddress = findViewById(R.id.fAddress)
        val spinner = findViewById<Spinner>(R.id.photos)

        val dir = File(filesDir, "originals")
        keys = dir.listFiles { f -> f.extension == "jpg" }
            ?.map { it.nameWithoutExtension }?.sortedDescending() ?: emptyList()

        if (keys.isEmpty()) {
            Toast.makeText(this, "No photos taken with the app yet", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, keys)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = load(keys[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    private fun load(key: String) {
        val dir = File(filesDir, "originals")
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

    private fun save() {
        val bmp = current ?: return
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
