package com.nizar.geostamp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nizar.geostamp.R
import com.nizar.geostamp.data.PhotoStore
import kotlin.concurrent.thread

/**
 * Grid of every photo and video taken, imported or edited in the app.
 * Tap a photo to edit its watermark, tap a video to play it, long-press to remove.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var items: List<PhotoStore.Item> = emptyList()
    private val cache = LruCache<String, Bitmap>(80)
    private var cell = 0

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val root: FrameLayout
            val iv: ImageView
            val badge: TextView
            if (convertView is FrameLayout) {
                root = convertView
                iv = root.getChildAt(0) as ImageView
                badge = root.getChildAt(1) as TextView
            } else {
                root = FrameLayout(this@GalleryActivity).apply {
                    layoutParams = AbsListView.LayoutParams(cell, cell)
                }
                iv = ImageView(this@GalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF222222.toInt())
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                badge = TextView(this@GalleryActivity).apply {
                    text = "▶ Video"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xAA000000.toInt())
                    setPadding(12, 4, 12, 4)
                    textSize = 12f
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.START
                    )
                }
                root.addView(iv)
                root.addView(badge)
            }

            val item = items[position]
            badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            iv.tag = item.id
            val cached = cache.get(item.id)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageBitmap(null)
                thread {
                    val b = BitmapFactory.decodeFile(item.thumb.absolutePath)
                    if (b != null) {
                        cache.put(item.id, b)
                        runOnUiThread { if (iv.tag == item.id) iv.setImageBitmap(b) }
                    }
                }
            }
            return root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        cell = resources.displayMetrics.widthPixels / 3

        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val item = items[pos]
            if (item.isVideo) {
                startActivity(
                    Intent(this, VideoPlayerActivity::class.java)
                        .putExtra(VideoPlayerActivity.EXTRA_PATH, item.image.absolutePath)
                )
            } else {
                startActivity(
                    Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, item.id)
                )
            }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val item = items[pos]
            AlertDialog.Builder(this)
                .setTitle("Remove from app gallery?")
                .setMessage("The copy saved on your phone (Pictures/GeoStamp or Movies/GeoStamp) is not deleted.")
                .setPositiveButton("Remove") { _, _ ->
                    PhotoStore.delete(this, item.id)
                    cache.remove(item.id)
                    reload()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        findViewById<Button>(R.id.importPhotos).setOnClickListener {
            startActivity(
                Intent(this, BatchActivity::class.java).putExtra(BatchActivity.EXTRA_AUTO_PICK, true)
            )
        }
        findViewById<Button>(R.id.importVideo).setOnClickListener {
            startActivity(
                Intent(this, VideoEditActivity::class.java).putExtra(VideoEditActivity.EXTRA_AUTO_PICK, true)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        thread {
            val list = PhotoStore.list(this)
            runOnUiThread {
                items = list
                adapter.notifyDataSetChanged()
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
