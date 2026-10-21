package com.nizar.geostamp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.nizar.geostamp.render.Stamper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-private storage behind the in-app gallery (photos and videos).
 *
 *   originals/<key>.jpg   clean photo, no watermark (so it can be re-stamped)
 *   stamped/<id>.jpg      finished photo with watermark (one per gallery entry)
 *   stamped/<id>.mp4      finished video with watermark
 *   stamped/<id>.txt      line 0 = original key (empty for videos), lines 1-4 = text/date/gps/address
 *   thumbs/<id>.jpg       small preview for the grid
 *
 * Every finished photo and video is also copied to the system gallery
 * (Pictures/GeoStamp and Movies/GeoStamp).
 */
object PhotoStore {
    private const val THUMB_SIZE = 360
    private val counter = AtomicInteger(0)

    data class Item(
        val id: String,
        val image: File,
        val thumb: File,
        val originalKey: String,
        val fields: StampFields,
        val isVideo: Boolean = false
    )

    private fun dir(ctx: Context, name: String) = File(ctx.filesDir, name).apply { mkdirs() }

    fun newKey(): String {
        val t = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${t}_${System.currentTimeMillis() % 1000}_${counter.incrementAndGet()}"
    }

    fun saveOriginal(ctx: Context, key: String, bmp: Bitmap) {
        File(dir(ctx, "originals"), "$key.jpg").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

    fun loadOriginal(ctx: Context, key: String): Bitmap? =
        BitmapFactory.decodeFile(File(dir(ctx, "originals"), "$key.jpg").absolutePath)

    /** Stamps [clean] with [fields], adds it to the in-app gallery and the system gallery. Returns the new id. */
    fun saveResult(ctx: Context, originalKey: String, clean: Bitmap, fields: StampFields): String {
        val id = newKey()
        val stamped = Stamper.draw(clean, fields.lines())

        File(dir(ctx, "stamped"), "$id.jpg").outputStream().use {
            stamped.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        File(dir(ctx, "stamped"), "$id.txt")
            .writeText((listOf(originalKey) + fields.serialize()).joinToString("\n"))
        writeThumb(ctx, id, stamped)

        Stamper.saveToGallery(ctx, stamped, "GeoStamp_$id.jpg")
        return id
    }

    /** Adds a finished (already watermarked) video to the in-app gallery and the system gallery. */
    fun saveVideoResult(ctx: Context, videoFile: File, fields: StampFields): String {
        val id = newKey()
        val dest = File(dir(ctx, "stamped"), "$id.mp4")
        if (!videoFile.renameTo(dest)) {
            videoFile.copyTo(dest, overwrite = true)
            videoFile.delete()
        }
        dest.setLastModified(System.currentTimeMillis())
        File(dir(ctx, "stamped"), "$id.txt")
            .writeText((listOf("") + fields.serialize()).joinToString("\n"))
        VideoInfo.frame(ctx, Uri.fromFile(dest))?.let { writeThumb(ctx, id, it) }

        Stamper.saveVideoToGallery(ctx, dest, "GeoStamp_$id.mp4")
        return id
    }

    private fun writeThumb(ctx: Context, id: String, source: Bitmap) {
        val scale = THUMB_SIZE.toFloat() / maxOf(source.width, source.height)
        val thumb = if (scale < 1f)
            Bitmap.createScaledBitmap(
                source, (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1), true
            ) else source
        File(dir(ctx, "thumbs"), "$id.jpg").outputStream().use {
            thumb.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
    }

    /** Newest first. */
    fun list(ctx: Context): List<Item> =
        dir(ctx, "stamped").listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { toItem(ctx, it.nameWithoutExtension, it) }

    fun get(ctx: Context, id: String): Item? {
        val jpg = File(dir(ctx, "stamped"), "$id.jpg")
        val mp4 = File(dir(ctx, "stamped"), "$id.mp4")
        return when {
            jpg.exists() -> toItem(ctx, id, jpg)
            mp4.exists() -> toItem(ctx, id, mp4)
            else -> null
        }
    }

    /** Removes the entry from the in-app gallery. The copy in the system gallery is left alone. */
    fun delete(ctx: Context, id: String) {
        val item = get(ctx, id) ?: return
        item.image.delete()
        File(dir(ctx, "stamped"), "$id.txt").delete()
        item.thumb.delete()
        if (item.originalKey.isNotEmpty() && list(ctx).none { it.originalKey == item.originalKey }) {
            File(dir(ctx, "originals"), "${item.originalKey}.jpg").delete()
        }
    }

    private fun toItem(ctx: Context, id: String, file: File): Item {
        val meta = File(file.parentFile, "$id.txt")
        val lines = if (meta.exists()) meta.readText().split("\n") else emptyList()
        return Item(
            id = id,
            image = file,
            thumb = File(dir(ctx, "thumbs"), "$id.jpg"),
            originalKey = lines.getOrElse(0) { "" },
            fields = StampFields.from(lines.drop(1)),
            isVideo = file.extension == "mp4"
        )
    }
}
