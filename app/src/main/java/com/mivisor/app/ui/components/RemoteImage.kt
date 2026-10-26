package com.mivisor.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cargador de logos minimalista para TV (sin Coil/Glide: ~1 MB menos y RAM bajo control).
 *  - Decodifica al tamaño en que se pinta (inSampleSize): un escudo de 512 px ocupa como uno de 64.
 *  - Caché en memoria acotada (1/32 del heap, máx. 6 MB) y caché en disco (máx. 300 ficheros).
 *  - Si falla (sin red, SVG, 404…) se muestra el [fallback] y no se reintenta en esa sesión.
 */
object ImageLoader {
    private val memory: LruCache<String, Bitmap> by lazy {
        val kb = (Runtime.getRuntime().maxMemory() / 1024 / 32).toInt().coerceIn(1024, 6 * 1024)
        object : LruCache<String, Bitmap>(kb) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }
    private val failed = HashSet<String>()
    private val lock = Mutex()

    fun peek(url: String?, sizePx: Int): ImageBitmap? =
        url?.let { memory.get(key(it, sizePx))?.asImageBitmap() }

    suspend fun load(context: Context, url: String, sizePx: Int): Bitmap? {
        val key = key(url, sizePx)
        memory.get(key)?.let { return it }
        if (synchronized(failed) { url in failed }) return null
        return withContext(Dispatchers.IO) {
            val file = lock.withLock { download(context, url) }
            val bitmap = file?.let { decode(it, sizePx) }
            if (bitmap == null) synchronized(failed) { failed += url } else memory.put(key, bitmap)
            bitmap
        }
    }

    private fun download(context: Context, url: String): File? {
        // "img2": la caché anterior podía guardar la imagen de aviso de Imgur ("removed.png") bajo la URL del logo.
        runCatching { File(context.cacheDir, "img").takeIf { it.isDirectory }?.deleteRecursively() }
        val dir = File(context.cacheDir, "img2").apply { mkdirs() }
        val file = File(dir, sha1(url))
        if (file.isFile && file.length() > 0) return file
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "MiVisor/4")
                if (connection.responseCode != 200) return null
                // Imagen borrada: el servidor redirige a su imagen de aviso ("The image you are requesting does not
                // exist…"). Eso NO es un logo: se trata como fallo y se pinta el respaldo.
                if (com.mivisor.app.data.logos.LogoCatalog.isPlaceholder(connection.url?.toString())) return null
                val tmp = File(dir, file.name + ".tmp")
                connection.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("Imagen demasiado grande")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                tmp.renameTo(file)
                prune(dir)
                file
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(file: File, sizePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun key(url: String, sizePx: Int) = "$sizePx|$url"

    private fun sha1(text: String): String = MessageDigest.getInstance("SHA-1")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val MAX_BYTES = 1_000_000
    private const val MAX_FILES = 300
}

/** Logo remoto con respaldo (iniciales) mientras carga o si no hay URL. */
@Composable
fun RemoteImage(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    /** true = recorta para llenar (retratos); false = encaja entera (escudos y logos). */
    crop: Boolean = false,
    fallback: @Composable () -> Unit
) {
    if (url.isNullOrBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(ImageLoader.peek(url, px), url, px) {
        if (value == null) value = ImageLoader.load(context, url, px)?.asImageBitmap()
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.size(size), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        } else {
            fallback()
        }
    }
}
