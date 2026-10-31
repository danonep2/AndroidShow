package com.androidshow.alexa.show

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidshow.alexa.net.arr
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Dim = Color(0xFFB0B8C4)

/** Renderiza um cartão TemplateRuntime.RenderTemplate conforme o `type`. */
@Composable
fun TemplateCard(t: JsonObject, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header(t)
        when (t.str("type")) {
            "WeatherTemplate" -> Weather(t)
            "ListTemplate1" -> ListBody(t)
            else -> Body(t)
        }
    }
}

@Composable
private fun Header(t: JsonObject) {
    val title = t.obj("title")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        t.obj("skillIcon")?.let { RemoteImage(it.imageUrl(), Modifier.size(48.dp)) }
        Column {
            Text(title?.str("mainTitle").orEmpty(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            title?.str("subTitle")?.let { Text(it, color = Dim) }
        }
    }
}

/** BodyTemplate1 (só texto) e BodyTemplate2 (texto + imagem); fallback para tipos desconhecidos. */
@Composable
private fun Body(t: JsonObject) {
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        t.obj("image")?.let { RemoteImage(it.imageUrl(), Modifier.weight(1f).fillMaxHeight()) }
        Text(
            t.str("textField").orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun ListBody(t: JsonObject) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(t.arr("listItems").orEmpty().mapNotNull { it as? JsonObject }) { item ->
            Row(Modifier.fillMaxWidth()) {
                Text(item.str("leftTextField").orEmpty(), color = Dim, modifier = Modifier.padding(end = 16.dp))
                Text(item.str("rightTextField").orEmpty(), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun Weather(t: JsonObject) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        RemoteImage(t.obj("currentWeatherIcon")?.imageUrl(), Modifier.size(140.dp))
        Column {
            Text(t.str("currentWeather").orEmpty(), style = MaterialTheme.typography.displayLarge)
            Text(t.str("description").orEmpty(), color = Dim)
            Text(
                "↑ ${t.obj("highTemperature")?.str("value").orEmpty()}   ↓ ${t.obj("lowTemperature")?.str("value").orEmpty()}",
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
    Spacer(Modifier.size(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        t.arr("weatherForecast").orEmpty().mapNotNull { it as? JsonObject }.take(6).forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.str("day").orEmpty(), color = Dim)
                RemoteImage(day.obj("image")?.imageUrl(), Modifier.size(56.dp))
                Text("${day.str("highTemperature").orEmpty()} / ${day.str("lowTemperature").orEmpty()}")
            }
        }
    }
}

/** TemplateRuntime.RenderPlayerInfo: capa, faixa, artista e provedor. */
@Composable
fun PlayerCard(info: JsonObject, modifier: Modifier = Modifier) {
    val content = info.obj("content") ?: info
    Row(
        modifier.fillMaxSize().padding(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        RemoteImage(content.obj("art")?.imageUrl(), Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content.str("header")?.let { Text(it, color = Dim) }
            Text(content.str("title").orEmpty(), style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            content.str("titleSubtext1")?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            content.str("titleSubtext2")?.let { Text(it, color = Dim) }
            content.obj("provider")?.str("name")?.let { Text(it, color = Dim) }
        }
    }
}

/**
 * Fallback para documentos APL enquanto o renderizador completo não está integrado: mostra os
 * textos e a primeira imagem encontrados nos datasources (ou no próprio documento).
 */
@Composable
fun AplFallback(screen: ShowScreen.Apl, modifier: Modifier = Modifier) {
    val source = screen.datasources ?: screen.document
    val texts = mutableListOf<String>()
    val images = mutableListOf<String>()
    collect(source, texts, images)
    Row(modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        images.firstOrNull()?.let { RemoteImage(it, Modifier.weight(1f).fillMaxHeight()) }
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            texts.take(8).forEachIndexed { i, s ->
                Text(s, style = if (i == 0) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private val TEXT_KEYS = setOf("text", "title", "primaryText", "secondaryText", "tertiaryText", "headerTitle", "headerSubtitle", "mainTitle", "subTitle", "textField")
private val IMAGE_KEYS = setOf("url", "imageSource", "backgroundImageSource", "src", "headerAttributionImage")

private fun collect(e: JsonElement, texts: MutableList<String>, images: MutableList<String>, key: String? = null) {
    when (e) {
        is JsonObject -> e.forEach { (k, v) -> collect(v, texts, images, k) }
        is JsonArray -> e.forEach { collect(it, texts, images, key) }
        is JsonPrimitive -> if (e.isString) {
            val s = e.content
            when {
                key in IMAGE_KEYS && s.startsWith("https://") -> images += s
                key in TEXT_KEYS && s.isNotBlank() && !s.startsWith("\${") -> texts += s.replace(Regex("<[^>]+>"), "")
            }
        }
    }
}

/** Escolhe a maior fonte de `{sources:[{url,size|widthPixels}]}` (a última costuma ser a maior). */
private fun JsonObject.imageUrl(): String? =
    arr("sources")?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull() ?: str("url")
