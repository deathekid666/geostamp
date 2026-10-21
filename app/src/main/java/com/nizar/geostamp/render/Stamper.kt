package com.nizar.geostamp.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.provider.MediaStore
import java.io.File

/** Draws the watermark and writes finished photos and videos to the system gallery. */
object Stamper {

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Returns a new bitmap with the lines drawn bottom-right. The input is not modified. */
    fun draw(src: Bitmap, lines: List<String>): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        if (lines.isNotEmpty()) render(Canvas(bmp), bmp.width, bmp.height, lines, bmp.width / 30f)
        return bmp
    }

    /** A transparent full-frame bitmap holding only the watermark, used as a video overlay. */
    fun overlayBitmap(width: Int, height: Int, lines: List<String>): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (lines.isNotEmpty()) render(Canvas(bmp), width, height, lines, width / 22f)
        return bmp
    }

    private fun render(canvas: Canvas, w: Int, h: Int, lines: List<String>, baseSize: Float) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = baseSize
            textAlign = Paint.Align.RIGHT
        }
        // Shrink the text if the longest line (usually the address) would not fit.
        val widest = lines.maxOf { text.measureText(it) }
        if (widest > w * 0.92f) text.textSize = baseSize * (w * 0.92f / widest)
        val textSize = text.textSize
        text.setShadowLayer(4f, 2f, 2f, Color.BLACK)

        val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        val pad = textSize * 0.6f
        val lineH = textSize * 1.25f
        val boxH = lines.size * lineH + pad * 2
        val boxW = (lines.maxOf { text.measureText(it) } + pad * 2).coerceAtMost(w.toFloat())
        val top = h - boxH
        canvas.drawRect(w - boxW, top, w.toFloat(), h.toFloat(), bg)
        lines.forEachIndexed { i, s ->
            canvas.drawText(s, w - pad, top + pad + lineH * (i + 1) - textSize * 0.25f, text)
        }
    }

    fun saveToGallery(ctx: Context, bmp: Bitmap, fileName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoStamp")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveVideoToGallery(ctx: Context, file: File, fileName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GeoStamp")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
