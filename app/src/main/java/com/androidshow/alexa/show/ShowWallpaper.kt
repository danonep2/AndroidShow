package com.androidshow.alexa.show

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.androidshow.alexa.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Papel de parede da tela parada do modo Show. Guarda uma cópia reduzida da foto escolhida em
 * files/: a permissão de leitura da galeria acaba quando o seletor fecha.
 */
class ShowWallpaper(private val context: Context, scope: CoroutineScope) {
    private val file = File(context.filesDir, "show_wallpaper.jpg")
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            if (file.exists()) _image.value = BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }
    }

    /** Copia a foto escolhida; devolve false se não deu para ler. */
    suspend fun set(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(uri)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            _image.value = bitmap.asImageBitmap()
        }.onFailure { DiagLog.w("show", "papel de parede não carregou", it) }.isSuccess
    }

    fun clear() {
        file.delete()
        _image.value = null
    }

    /** Reduz para no máximo [MAX_SIDE] px no lado maior; o ImageDecoder já aplica a rotação do EXIF. */
    private fun decode(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("imagem ilegível")
        }

    private companion object {
        const val MAX_SIDE = 2400
    }
}
