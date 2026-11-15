package com.miniprojector

import android.content.Context
import android.net.Uri

/** Settings remembered between sessions: once the box is set up, nothing needs adjusting again. */
class ProjectorSettings(context: Context) {
    private val prefs = context.getSharedPreferences("projector", Context.MODE_PRIVATE)

    var flipVertical: Boolean
        get() = prefs.getBoolean(KEY_FLIP_V, true)
        set(value) = prefs.edit().putBoolean(KEY_FLIP_V, value).apply()

    var flipHorizontal: Boolean
        get() = prefs.getBoolean(KEY_FLIP_H, false)
        set(value) = prefs.edit().putBoolean(KEY_FLIP_H, value).apply()

    var zoom: Float
        get() = prefs.getFloat(KEY_ZOOM, 1f)
        set(value) = prefs.edit().putFloat(KEY_ZOOM, value).apply()

    var keystone: Float
        get() = prefs.getFloat(KEY_KEYSTONE, 0f)
        set(value) = prefs.edit().putFloat(KEY_KEYSTONE, value).apply()

    var lastMedia: Uri?
        get() = prefs.getString(KEY_LAST_URI, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_LAST_URI, value?.toString()).apply()

    var lastMediaIsVideo: Boolean
        get() = prefs.getBoolean(KEY_LAST_IS_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_IS_VIDEO, value).apply()

    private companion object {
        const val KEY_FLIP_V = "flip_vertical"
        const val KEY_FLIP_H = "flip_horizontal"
        const val KEY_ZOOM = "zoom"
        const val KEY_KEYSTONE = "keystone"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_LAST_IS_VIDEO = "last_is_video"
    }
}
