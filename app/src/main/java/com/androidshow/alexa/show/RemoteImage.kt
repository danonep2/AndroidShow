package com.androidshow.alexa.show

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.androidshow.alexa.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Carregador de imagens mínimo (OkHttp + cache LRU em memória), no lugar de Coil/Glide:
 * os cartões da Alexa têm poucas imagens e não precisam de cache em disco.
 */
object RemoteImages {
    lateinit var http: OkHttpClient

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(url: String, maxSide: Int = 1280): Bitmap? {
        cache.get(url)?.let { return it }
        val bytes = runCatching {
            http.newCall(Request.Builder().url(url).build()).await().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.Default) { decode(bytes, maxSide) } ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = url?.let { RemoteImages.load(it) } }
    val b = bitmap
    if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier)
}
