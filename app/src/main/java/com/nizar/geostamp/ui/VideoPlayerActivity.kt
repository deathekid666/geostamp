package com.nizar.geostamp.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Plays a video from the in-app gallery. */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            Toast.makeText(this, "Video not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val container = FrameLayout(this)
        val video = VideoView(this)
        container.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
        )
        setContentView(container)

        val controls = MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        video.setVideoPath(path)
        video.setOnPreparedListener { it.start() }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Cannot play this video", Toast.LENGTH_LONG).show()
            true
        }
    }
}
