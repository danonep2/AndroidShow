package com.androidshow.alexa.ui.ios

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidshow.alexa.ui.Palette

/**
 * Peças no estilo dos Ajustes do iOS (modo escuro): página com título grande, grupos de cantos
 * arredondados com separadores recuados, interruptor e ícones em quadrados coloridos.
 */
object Ios {
    val Background = Color.Black
    val Group = Color(0xFF1C1C1E)
    val GroupPressed = Color(0xFF2C2C2E)
    val Separator = Color(0xFF38383A)
    val Label = Color.White
    val SecondaryLabel = Color(0x99EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    val Destructive = Color(0xFFFF453A)
    val Gray = Color(0xFF8E8E93)
    /** Cor de links e marcações: o roxo primário clareado para ler bem sobre o preto. */
    val Tint = Palette.bright(Palette.Primary, 0.5f)
    val SwitchOn = Palette.bright(Palette.Primary, 0.25f)
}

/** Página com barra de navegação (voltar à esquerda) e título grande que passa para a barra ao rolar. */
@Composable
fun IosPage(
    title: String,
    back: String? = null,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 40 } }
    Column(Modifier.fillMaxSize().background(Ios.Background).safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            if (onBack != null) {
                Row(
                    Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                        .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Ios.Tint, modifier = Modifier.size(34.dp))
                    Text(back ?: "Voltar", color = Ios.Tint, fontSize = 17.sp, modifier = Modifier.offset(x = (-4).dp))
                }
            }
            val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "título")
            Text(
                title, color = Ios.Label.copy(alpha = titleAlpha), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Text(
                    title, color = Ios.Label, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                )
            }
            content()
        }
    }
}

/** Grupo de linhas com cabeçalho e rodapé opcionais. */
fun LazyListScope.section(header: String? = null, footer: String? = null, rows: @Composable () -> Unit) {
    item {
        Column(Modifier.padding(top = if (header == null) 28.dp else 22.dp)) {
            if (header != null) {
                Text(
                    header.uppercase(), color = Ios.SecondaryLabel, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 7.dp),
                )
            }
            IosGroup(rows)
            if (footer != null) {
                Text(
                    footer, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp),
                )
            }
        }
    }
}

/**
 * Cada linha desenha o separador no próprio topo; o grupo sobe o conteúdo 1 px e o recorte
 * esconde o separador da primeira linha.
 */
@Composable
fun IosGroup(rows: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.Group)) {
        Column(
            Modifier.layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, (p.height - 1).coerceAtLeast(0)) { p.place(0, -1) }
            },
        ) { rows() }
    }
}

/** Ícone branco num quadrado colorido, como nos Ajustes do iOS. */
@Composable
fun IconTile(icon: ImageVector, color: Color) {
    Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/**
 * Linha de lista. Com [onClick] fica destacada ao tocar e mostra a seta; [trailing] substitui
 * valor e seta (interruptor, marcação).
 */
@Composable
fun IosRow(
    title: String,
    icon: ImageVector? = null,
    iconColor: Color = Palette.Primary,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Ios.Label,
    centered: Boolean = false,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inset = if (icon != null) 60.dp else 16.dp
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) Ios.GroupPressed else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .drawBehind { drawLine(Ios.Separator, Offset(inset.toPx(), 0.5f), Offset(size.width, 0.5f), 1f) }
            .heightIn(min = 46.dp)
            .padding(start = 16.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, iconColor)
            Spacer(Modifier.width(15.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, color = titleColor, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
            )
            if (subtitle != null) Text(subtitle, color = Ios.SecondaryLabel, fontSize = 13.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else {
            if (value != null) Text(value, color = Ios.SecondaryLabel, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            if (onClick != null && chevron && !centered) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ios.TertiaryLabel,
                    modifier = Modifier.padding(start = 2.dp).size(24.dp).offset(x = 4.dp),
                )
            }
        }
    }
}

/** Interruptor no formato do iOS (51 × 31 dp), na cor primária. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "interruptor",
    )
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(lerp(Color(0xFF39393D), Ios.SwitchOn, t))
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(2.dp),
    ) {
        Box(Modifier.offset(x = 20.dp * t).size(27.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** Botão principal cheio, largura total (ex.: "Entrar com Amazon"). */
@Composable
fun IosFilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ios.SwitchOn.copy(alpha = if (pressed) 0.7f else 1f))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
