package com.androidshow.alexa.show

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

/**
 * Home do modo Show: cartões da nuvem (clima, música, receitas...) girando a cada 10 s, com o
 * relógio no canto, como no Echo Show. Renderização nativa provisória dos dados do UpdateData,
 * até o documento APL da home (SPA) ser desenhado pelo renderizador APL.
 */
@Composable
fun HomeScreen(cards: List<JsonObject>, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) {
        Clock(modifier)
        return
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(cards.size) {
        while (true) {
            delay(ROTATE_MS)
            index = (index + 1) % cards.size
        }
    }
    Box(modifier.fillMaxSize()) {
        AnimatedContent(cards[index % cards.size], label = "home", transitionSpec = { fadeIn() togetherWith fadeOut() }) {
            HomeCard(it)
        }
        SmallClock(Modifier.align(Alignment.TopStart).padding(32.dp))
    }
}

/**
 * Um cartão da home. Formato observado no UpdateData (item pull:// → data → data), por namespace:
 * WeatherDetail (forecast/alert), Media (reproduzida recentemente) e GenericMedia (receitas).
 */
@Composable
private fun HomeCard(item: JsonObject) {
    val outer = item.obj("data") ?: item
    val d = outer.obj("data") ?: outer
    val image = d.str("background_image_url") ?: d.str("cardAlbumImage")
    val header = d.str("cardTitle") ?: d.str("card_title") ?: d.str("headerText")
    val primary = d.str("cardPrimaryText") ?: d.str("title")
    val secondary = d.str("weatherSummary") ?: d.str("cardSecondaryText") ?: d.str("attribution")
    val third = d.str("cardThirdlineText1") ?: d.str("alertSource")
    val big = d.str("currentTemperature")
    val icon = d.str("currentTemperatureIcon")
    val hint = (d.str("alexaHintText") ?: d.str("hint") ?: d.str("hintText"))?.replace("{WakeWord}", "Alexa")
    val highLow = listOfNotNull(d.str("highTemperature"), d.str("lowTemperature"))
    val album = d.str("cardAlbumImage") != null

    Box(Modifier.fillMaxSize()) {
        if (image != null && !album) {
            RemoteImage(image, Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0xE6000000)))))
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 56.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            if (album) RemoteImage(image, Modifier.size(220.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = Color(0xFF8FB8FF)) }
                if (big != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        icon?.let { Text(it, fontSize = 64.sp) }
                        Text(big, fontSize = 88.sp, fontWeight = FontWeight.Light)
                        if (highLow.isNotEmpty()) Column { highLow.forEach { Text(it, style = MaterialTheme.typography.titleLarge) } }
                    }
                }
                primary?.let { Text(it, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                secondary?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = Color(0xFFCBD3DD), maxLines = 2) }
                third?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color(0xFF9AA4B1)) }
                hint?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF8FB8FF), modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val now = rememberMinute()
    val date = Date(now)
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(date), fontSize = 120.sp, fontWeight = FontWeight.Light)
        Text(DateFormat.getDateInstance(DateFormat.FULL).format(date), style = MaterialTheme.typography.headlineSmall, color = Color(0xFFB0B8C4))
        Text("Diga \"Alexa\" ou toque no microfone", modifier = Modifier.padding(top = 32.dp), color = Color(0xFF6B7380))
    }
}

@Composable
private fun SmallClock(modifier: Modifier) {
    val now = rememberMinute()
    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), modifier, fontSize = 40.sp, fontWeight = FontWeight.Light)
}

@Composable
private fun rememberMinute(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    return now
}

private const val ROTATE_MS = 10_000L
