package com.radiomonde

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small loader for station logos: downloads in the background, scales down, keeps a memory cache. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView, placeholder: Int) {
        view.tag = url
        val cached = if (url.isBlank()) null else cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(placeholder)
        if (url.isBlank() || !url.startsWith("http") || synchronized(failed) { url in failed }) return
        executor.execute {
            val bitmap = try { download(url) } catch (_: Throwable) { null }
            if (bitmap == null) synchronized(failed) { failed.add(url) } else cache.put(url, bitmap)
            main.post { if (view.tag == url && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun download(url: String): Bitmap? {
        var target = url
        repeat(4) { // follow redirects, including http <-> https
            val c = URL(target).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000
                c.readTimeout = 8000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "RadioMonde/1.0 (Android)")
                val code = c.responseCode
                if (code in 300..399) {
                    target = URL(URL(target), c.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size > 3 * 1024 * 1024) return null
                return decodeScaled(bytes, 160)
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun decodeScaled(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = size.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
    }
}
