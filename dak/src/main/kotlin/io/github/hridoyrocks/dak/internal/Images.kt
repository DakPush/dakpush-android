package io.github.hridoyrocks.dak.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Downloading and decoding pictures, for notifications and in-app messages. */
internal object DakImages {
    const val MAX_BYTES = 5 * 1024 * 1024

    // Disconnects a download that runs past its deadline; a blocked read returns then.
    private val watchdog: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dak-image-deadline").apply { isDaemon = true } }
    }

    /**
     * The bytes at [url], or null: not https, not 200, larger than
     * [maxBytes], or not all there within [deadlineMs]. Blocks; never throws.
     */
    fun download(url: String?, deadlineMs: Long, maxBytes: Int = MAX_BYTES): ByteArray? {
        if (url.isNullOrEmpty() || !url.startsWith("https://")) return null
        val deadline = SystemClock.elapsedRealtime() + deadlineMs
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = deadlineMs.toInt().coerceAtMost(5000)
            conn.readTimeout = deadlineMs.toInt().coerceAtMost(5000)
            conn.instanceFollowRedirects = true
            val stop = watchdog.schedule({ conn.disconnect() }, deadlineMs, TimeUnit.MILLISECONDS)
            try {
                if (conn.responseCode != 200) return null
                val length = conn.getHeaderField("Content-Length")?.toLongOrNull()
                if (length != null && length > maxBytes) return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes || SystemClock.elapsedRealtime() > deadline) return null
                    }
                }
                // A redirect that ended on http is not trusted either.
                if (conn.url.protocol != "https") return null
                out.toByteArray()
            } finally {
                stop.cancel(false)
                conn.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Decodes [bytes] no smaller than [minWidth] px wide (when the picture
     * is that wide) and no side over [maxSide] px; null when they are not a
     * picture or memory runs out.
     */
    fun decode(bytes: ByteArray, minWidth: Int = 0, maxSide: Int = 4096): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (true) {
                val next = sample * 2
                val tooBig = bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide
                if (!tooBig && (minWidth <= 0 || bounds.outWidth / next < minWidth)) break
                sample = next
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (t: Throwable) {
        // OutOfMemoryError included: the message goes without the picture.
        null
    }
}

/**
 * In-app images: kept on disk (the cache folder, so Android may clear it)
 * for the messages in the list, fetched ahead for the ones that may show,
 * and the decoded ones in memory, so a rotation or a message shown again
 * does not wait.
 */
internal class InAppImages(private val dir: File, private val log: DakLog) : InAppImagePrefetch {
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dak-in-app-image").apply { isDaemon = true }
    }
    private val memory = object : LruCache<String, Bitmap>(memoryBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The picture decoded for [widthPx], if it is in memory. */
    fun cached(url: String, widthPx: Int): Bitmap? = memory.get(key(url, widthPx))

    /** Loads the picture in the background and calls [done] on the main thread with it or null. */
    fun load(url: String, widthPx: Int, deadlineMs: Long, done: (Bitmap?) -> Unit) {
        cached(url, widthPx)?.let {
            done(it)
            return
        }
        executor.execute {
            val bitmap = loadNow(url, widthPx, deadlineMs)
            main.post { done(bitmap) }
        }
    }

    private fun loadNow(url: String, widthPx: Int, deadlineMs: Long): Bitmap? {
        val file = file(url)
        var bytes = try {
            if (file.isFile) file.readBytes() else null
        } catch (e: IOException) {
            null
        }
        if (bytes == null) {
            bytes = DakImages.download(url, deadlineMs) ?: return null
            store(file, bytes)
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        val bitmap = DakImages.decode(bytes, widthPx) ?: return null
        memory.put(key(url, widthPx), bitmap)
        return bitmap
    }

    /** Keeps the files of [urls] (and no others) and downloads [prefetch] that are missing. */
    override fun update(urls: Set<String>, prefetch: List<String>) {
        executor.execute {
            val keep = urls.map { name(it) }.toSet()
            dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            for (url in prefetch) {
                val file = file(url)
                if (file.isFile) continue
                DakImages.download(url, PREFETCH_DEADLINE_MS)?.let { store(file, it) }
            }
            trim()
        }
    }

    private fun store(file: File, bytes: ByteArray) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
            trim()
        } catch (e: IOException) {
            log.d("dak: could not keep an in-app image: $e")
        }
    }

    /** At most [MAX_DISK_BYTES] on disk; the least recently used go first. */
    private fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > MAX_DISK_BYTES) f.delete()
        }
    }

    private fun file(url: String) = File(dir, name(url))

    companion object {
        const val MAX_DISK_BYTES = 20L * 1024 * 1024
        const val PREFETCH_DEADLINE_MS = 20_000L

        private fun key(url: String, widthPx: Int) = "$widthPx $url"

        private fun name(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        /** An eighth of what the app may use, at most 32 MB. */
        private fun memoryBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()
    }
}
