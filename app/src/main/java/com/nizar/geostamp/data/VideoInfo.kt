package com.nizar.geostamp.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads basic facts about a video file or picked video. */
object VideoInfo {

    /** [width] and [height] are as shown on screen (rotation already applied). */
    class Probe(val width: Int, val height: Int, val durationMs: Long, val createdMillis: Long?)

    fun probe(ctx: Context, uri: Uri): Probe? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (w <= 0 || h <= 0) return null
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val created = try {
                val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { fmt.parse(it)?.time }
            } catch (_: Exception) {
                null
            }
            return Probe(w, h, duration, created)
        } catch (_: Exception) {
            return null
        } finally {
            r.release()
        }
    }

    /** First frame, scaled down to at most 800 px, or null. */
    fun frame(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 800, 800)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
