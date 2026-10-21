package com.nizar.geostamp.render

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.nizar.geostamp.data.StampFields
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Burns the watermark into a video. The date line can run like a clock: it starts at
 * [ClockSpec.startMillis] at the first frame and keeps counting until the video ends.
 */
@OptIn(UnstableApi::class)
object VideoWatermarker {

    data class ClockSpec(
        val text: String,
        val startMillis: Long,
        val running: Boolean,
        val gps: String,
        val address: String
    )

    /** Redraws the watermark once per second of video. */
    private class ClockOverlay(
        private val w: Int,
        private val h: Int,
        private val spec: ClockSpec
    ) : BitmapOverlay() {
        private var lastSecond = -1L
        private var lastBitmap: Bitmap? = null
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val sec = presentationTimeUs / 1_000_000L
            val cached = lastBitmap
            if (cached != null && sec == lastSecond) return cached
            val shown = spec.startMillis + if (spec.running) sec * 1000L else 0L
            val lines = StampFields(spec.text, fmt.format(Date(shown)), spec.gps, spec.address).lines()
            val bmp = Stamper.overlayBitmap(w, h, lines)
            lastSecond = sec
            lastBitmap = bmp
            return bmp
        }
    }

    /**
     * Starts the export on the calling (main) thread. [onResult] receives null on success or an
     * error message. Returns a function that cancels the export.
     */
    fun start(
        ctx: Context,
        input: Uri,
        output: File,
        width: Int,
        height: Int,
        spec: ClockSpec,
        onResult: (String?) -> Unit
    ): () -> Unit {
        val noop: () -> Unit = {}
        return try {
            val overlay = ClockOverlay(width, height, spec)
            val effects = Effects(
                emptyList<AudioProcessor>(),
                listOf<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setEffects(effects)
                .build()
            val transformer = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        onResult(null)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        onResult("Watermark failed: ${exportException.errorCodeName}")
                    }
                })
                .build()
            transformer.start(item, output.absolutePath)
            val cancel: () -> Unit = {
                try { transformer.cancel() } catch (_: Exception) {}
            }
            cancel
        } catch (e: Exception) {
            onResult("Watermark failed: ${e.message}")
            noop
        }
    }
}
