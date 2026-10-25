package app.torboxtv.ui

import android.content.Context
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.LruCache
import android.view.ViewTreeObserver
import android.widget.ImageView
import app.torboxtv.core.ArtworkUrls
import app.torboxtv.core.recoverArtwork
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Artwork is optional: failed images never hold up catalog or remote navigation. */
class Artwork(context: Context) {
    private val directory = File(context.cacheDir, "artwork").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = WeakHashMap<ImageView, Job>()
    private data class PendingImage(val urls: List<String>, val key: String, val width: Int)
    private val pending = WeakHashMap<ImageView, PendingImage>()
    private val root = (context as? Activity)?.window?.decorView
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { loadVisible() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { loadVisible() }
    private val slots = Semaphore(3)
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!ArtworkUrls.valid(url.toString())) {
                throw IOException("Unsupported artwork address")
            }
            chain.proceed(chain.request())
        }.build()
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        root?.viewTreeObserver?.addOnScrollChangedListener(scrollListener)
        root?.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)
    }

    fun bind(view: ImageView, url: String, backdrop: Boolean = false, id: String = "") {
        jobs.remove(view)?.cancel()
        pending.remove(view)
        view.setImageDrawable(null)
        val urls = ArtworkUrls.candidates(url, id)
        if (urls.isEmpty()) return
        val size = if (backdrop) 1280 else 320
        val key = "$size:${urls.joinToString("|")}"
        memory.get(key)?.let { view.setImageBitmap(it); return }
        pending[view] = PendingImage(urls, key, size)
        // Offscreen poster rows do not download dozens of images during startup.
        if (root != null) root.post { loadVisible() } else start(view, PendingImage(urls, key, size))
    }

    private fun loadVisible() {
        val visible = Rect()
        pending.entries.toList().forEach { (view, request) ->
            if (view.isShown && view.getGlobalVisibleRect(visible)) start(view, request)
        }
    }

    private fun start(view: ImageView, request: PendingImage) {
        pending.remove(view)
        jobs[view] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                slots.withPermit {
                    withTimeoutOrNull(28000) {
                        recoverArtwork(request.urls, read = { url ->
                            try { read(url, request.width) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        })
                    }
                }
            }
            ensureActive()
            bitmap?.let { memory.put(request.key, it); view.setImageBitmap(it) }
        }
    }

    private suspend fun read(url: String, width: Int): Bitmap? {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, hash)
        if (!file.exists()) {
            if (!download(url, file)) return null
            trimDisk()
        }
        currentCoroutineContext().ensureActive()
        file.setLastModified(System.currentTimeMillis())
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth.toLong() * options.outHeight > 80_000_000) {
            file.delete(); return null
        }
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        options.inSampleSize = 1
        val maxHeight = if (width > 320) 1440 else 960
        while (options.outWidth / options.inSampleSize > width * 2 || options.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2
        return BitmapFactory.decodeFile(file.path, options).also { if (it == null) file.delete() }
    }

    private suspend fun download(url: String, file: File): Boolean = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(false) }
            override fun onResponse(call: Call, response: Response) {
                var temp: File? = null
                var success = false
                try {
                    response.use {
                        val download = File.createTempFile(file.name, ".part", directory)
                        temp = download
                        val body = it.body
                        if (it.isSuccessful && body != null && body.contentLength() <= 8 * 1024 * 1024) {
                            body.byteStream().use { input -> download.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (continuation.isActive) {
                                    val n = input.read(buffer)
                                    if (n < 0) { success = true; break }
                                    total += n
                                    if (total > 8 * 1024 * 1024) break
                                    output.write(buffer, 0, n)
                                }
                            } }
                            success = success && continuation.isActive && download.renameTo(file)
                        }
                    }
                } catch (_: Exception) { success = false }
                finally { temp?.delete(); response.close() }
                if (continuation.isActive) continuation.resume(success)
            }
        })
    }

    private fun trimDisk() {
        val files = directory.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 40 * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    fun cancelPending() { jobs.values.toList().forEach { it.cancel() }; jobs.clear(); pending.clear() }
    fun close() {
        scope.cancel(); jobs.clear(); pending.clear(); memory.evictAll(); client.dispatcher.cancelAll()
        root?.viewTreeObserver?.takeIf { it.isAlive }?.let { observer -> observer.removeOnScrollChangedListener(scrollListener); observer.removeOnGlobalLayoutListener(layoutListener) }
    }
}
