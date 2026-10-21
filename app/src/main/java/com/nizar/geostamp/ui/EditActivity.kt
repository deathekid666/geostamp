package com.nizar.geostamp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoStore
import com.nizar.geostamp.data.StampFields
import kotlin.concurrent.thread

/** Change the watermark of a photo from the gallery. Always starts again from the clean original. */
class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var fText: EditText
    private lateinit var fDate: EditText
    private lateinit var fGps: EditText
    private lateinit var fAddress: EditText
    private var item: PhotoStore.Item? = null
    private var original: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        fText = findViewById(R.id.fText)
        fDate = findViewById(R.id.fDate)
        fGps = findViewById(R.id.fGps)
        fAddress = findViewById(R.id.fAddress)

        val id = intent.getStringExtra(EXTRA_ID)
        val it = id?.let { PhotoStore.get(this, it) }
        val orig = it?.let { i -> PhotoStore.loadOriginal(this, i.originalKey) }
        if (it == null || orig == null) {
            Toast.makeText(this, "Photo not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        item = it
        original = orig

        findViewById<ImageView>(R.id.image).setImageBitmap(BitmapFactory.decodeFile(it.image.absolutePath))
        fText.setText(it.fields.text)
        fDate.setText(it.fields.date)
        fGps.setText(it.fields.gps)
        fAddress.setText(it.fields.address)

        findViewById<Button>(R.id.saveNew).setOnClickListener { save(replace = false) }
        findViewById<Button>(R.id.replace).setOnClickListener { save(replace = true) }
    }

    private fun save(replace: Boolean) {
        val current = item ?: return
        val clean = original ?: return
        val fields = StampFields(
            fText.text.toString().trim(),
            fDate.text.toString().trim(),
            fGps.text.toString().trim(),
            fAddress.text.toString().trim()
        )
        thread {
            PhotoStore.saveResult(this, current.originalKey, clean, fields)
            if (replace) PhotoStore.delete(this, current.id)
            runOnUiThread {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
