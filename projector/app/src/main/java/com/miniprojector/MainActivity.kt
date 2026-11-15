package com.miniprojector

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch

/** Home screen: choose what to project and how the lens turns the picture. */
class MainActivity : Activity() {

    private lateinit var settings: ProjectorSettings
    private lateinit var replay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = ProjectorSettings(this)

        findViewById<Button>(R.id.pick_image).setOnClickListener { pick("image/*", REQUEST_IMAGE) }
        findViewById<Button>(R.id.pick_video).setOnClickListener { pick("video/*", REQUEST_VIDEO) }
        findViewById<Button>(R.id.show_pattern).setOnClickListener {
            startActivity(ProjectionActivity.focusPattern(this))
        }
        replay = findViewById(R.id.replay)
        replay.setOnClickListener {
            settings.lastMedia?.let { project(it, settings.lastMediaIsVideo) }
        }

        findViewById<Switch>(R.id.flip_vertical).apply {
            isChecked = settings.flipVertical
            setOnCheckedChangeListener { _, checked -> settings.flipVertical = checked }
        }
        findViewById<Switch>(R.id.flip_horizontal).apply {
            isChecked = settings.flipHorizontal
            setOnCheckedChangeListener { _, checked -> settings.flipHorizontal = checked }
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed from the projection screen.
        findViewById<Switch>(R.id.flip_vertical).isChecked = settings.flipVertical
        findViewById<Switch>(R.id.flip_horizontal).isChecked = settings.flipHorizontal
        replay.visibility = if (canReplay()) View.VISIBLE else View.GONE
    }

    private fun canReplay(): Boolean {
        val uri = settings.lastMedia ?: return false
        return contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    private fun pick(type: String, request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(type)
        startActivityForResult(intent, request)
    }

    @Deprecated("Platform API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val isVideo = requestCode == REQUEST_VIDEO
        // Keep access so "Play the last one again" still works after a restart.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        settings.lastMedia = uri
        settings.lastMediaIsVideo = isVideo
        project(uri, isVideo)
    }

    private fun project(uri: Uri, isVideo: Boolean) {
        startActivity(ProjectionActivity.media(this, uri, isVideo))
    }

    private companion object {
        const val REQUEST_IMAGE = 1
        const val REQUEST_VIDEO = 2
    }
}
