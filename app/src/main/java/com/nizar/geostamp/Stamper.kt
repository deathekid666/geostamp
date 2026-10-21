package com.nizar.geostamp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.provider.MediaStore

object Stamper {

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Returns a new bitmap with the lines drawn bottom-left. The input is not modified. */
    fun draw(src: Bitmap, lines: List<String>): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        if (lines.isEmpty()) return bmp
        val canvas = Canvas(bmp)

        val textSize = bmp.width / 30f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }

        val pad = textSize * 0.6f
        val lineH = textSize * 1.25f
        val boxH = lines.size * lineH + pad * 2
        val boxW = (lines.maxOf { text.measureText(it) } + pad * 2).coerceAtMost(bmp.width.toFloat())
        val top = bmp.height - boxH
        canvas.drawRect(0f, top, boxW, bmp.height.toFloat(), bg)
        lines.forEachIndexed { i, s ->
            canvas.drawText(s, pad, top + pad + lineH * (i + 1) - textSize * 0.25f, text)
        }
        return bmp
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
}
