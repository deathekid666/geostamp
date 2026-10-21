package com.nizar.geostamp.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoImporter
import com.nizar.geostamp.data.PhotoStore
import com.nizar.geostamp.data.StampFields
import kotlin.concurrent.thread

/**
 * Pick many photos (grid picker with tick marks), see all of them, drop the ones you don't want,
 * then give them all the same watermark.
 */
class BatchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_PICK = "auto_pick"
    }

    private val uris = mutableListOf<Uri>()
    private val cache = LruCache<Uri, Bitmap>(150)
    private var cell = 0

    private lateinit var grid: GridView
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var fText: EditText
    private lateinit var fDate: EditText
    private lateinit var fGps: EditText
    private lateinit var fAddress: EditText
    private lateinit var apply: Button

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { list ->
        if (list.isNotEmpty()) addUris(list)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = uris.size
        override fun getItem(position: Int) = uris[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val root: FrameLayout
            val iv: ImageView
            if (convertView is FrameLayout) {
                root = convertView
                iv = root.getChildAt(0) as ImageView
            } else {
                root = FrameLayout(this@BatchActivity).apply {
                    layoutParams = AbsListView.LayoutParams(cell, cell)
                }
                iv = ImageView(this@BatchActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF222222.toInt())
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val mark = TextView(this@BatchActivity).apply {
                    text = "✕"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xAA000000.toInt())
                    setPadding(16, 6, 16, 6)
                    textSize = 14f
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    )
                }
                root.addView(iv)
                root.addView(mark)
            }

            val uri = uris[position]
            iv.tag = uri
            val cached = cache.get(uri)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageBitmap(null)
                thread {
                    try {
                        val b = PhotoImporter.decodeSampled(this@BatchActivity, uri, 256)
                        cache.put(uri, b)
                        runOnUiThread { if (iv.tag == uri) iv.setImageBitmap(b) }
                    } catch (_: Throwable) {
                    }
                }
            }
            return root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch)
        grid = findViewById(R.id.grid)
        count = findViewById(R.id.count)
        status = findViewById(R.id.status)
        fText = findViewById(R.id.fText)
        fDate = findViewById(R.id.fDate)
        fGps = findViewById(R.id.fGps)
        fAddress = findViewById(R.id.fAddress)
        apply = findViewById(R.id.apply)
        cell = resources.displayMetrics.widthPixels / 3

        fText.setText(getSharedPreferences("geostamp", MODE_PRIVATE).getString("text", ""))

        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (pos in uris.indices) {
                uris.removeAt(pos)
                adapter.notifyDataSetChanged()
                updateCount()
            }
        }

        findViewById<Button>(R.id.choose).setOnClickListener { launchPicker() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            uris.clear()
            adapter.notifyDataSetChanged()
            updateCount()
        }
        apply.setOnClickListener { applyToAll() }

        updateCount()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_PICK, false)) {
            launchPicker()
        }
    }

    private fun launchPicker() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun addUris(list: List<Uri>) {
        for (u in list) if (u !in uris) uris.add(u)
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        count.text = if (uris.isEmpty()) "No photos selected"
        else "${uris.size} photo(s) selected - tap a photo to remove it"
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
        val list = uris.toList()
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
