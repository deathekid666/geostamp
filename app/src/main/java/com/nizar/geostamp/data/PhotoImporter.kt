package com.nizar.geostamp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.nizar.geostamp.render.Stamper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads a photo picked from the phone's gallery, together with its own date and GPS when it has them. */
object PhotoImporter {

    class Imported(val bitmap: Bitmap, val date: String, val gps: String)

    fun read(ctx: Context, uri: Uri): Imported {
        val exif = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }

        val degrees = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
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

        val bmp = Stamper.rotate(decodeSampled(ctx, uri, 4096), degrees)
        return Imported(bmp, date, gps)
    }

    /** Decodes [uri] scaled down so that its longest side is at most [maxSide] pixels. */
    fun decodeSampled(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Could not decode photo")
    }
}
