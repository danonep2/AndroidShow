package com.androidshow.alexa.show.recipes

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidshow.alexa.show.RemoteImage
import com.androidshow.alexa.ui.AppIcons
import com.androidshow.alexa.ui.Palette
import com.androidshow.alexa.ui.ios.Ios
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Receitas no modo Show, com visual próprio (não é cópia do Echo Show nem do app Windows):
 * cartões grandes e numerados para pedir por voz, detalhe com ingredientes que se marcam e um
 * modo passo a passo de letras grandes para ler de longe, com as mãos ocupadas.
 *
 * [onSelect] envia o toque à Alexa (UserEvent); devolve false quando o documento não diz como,
 * e aí o detalhe abre aqui mesmo com o que já veio na lista.
 */
@Composable
fun RecipeScreen(
    view: RecipeView,
    onSelect: (Recipe) -> Boolean,
    modifier: Modifier = Modifier,
    /** UserEvent com estes argumentos ("Iniciar receita", "Salvar receita", `["back"]`). */
    onAction: (JsonArray) -> Unit = {},
    /** Voltar à tela anterior da receita (o app volta na hora e avisa a nuvem com `["back"]`). */
    onNavigateBack: () -> Unit = {},
    /** Passo escolhido na tela (toque ou deslize) no passo a passo vindo da nuvem. */
    onStepSelected: (Recipe, Int) -> Unit = { _, _ -> },
    /** ExecuteCommands da nuvem para esta tela ("próximo passo", "mostrar ingredientes"). */
    commands: Flow<RecipeCommand> = emptyFlow(),
    /** Foto já vista desta receita (o passo a passo vem sem imagem [Observado]). */
    imageFor: (String) -> String? = { null },
) {
    val detail = view as? RecipeView.Detail
    // A nuvem reenvia o mesmo documento a cada passo: o estado segue a identidade da tela, não o objeto.
    var local by remember(view.key) { mutableStateOf<Recipe?>(null) }
    var cooking by remember(view.key) { mutableStateOf(detail?.cooking == true) }
    val recipe = detail?.recipe ?: local
    val nav = remember(view.key) { RecipeNav().apply { step = detail?.step ?: 0 } }
    val currentRecipe by rememberUpdatedState(recipe)
    val cloudCooking = detail?.cooking == true

    // Documento reenviado: o passo atual vem no visualContext (cookAlongStepOrdinal).
    LaunchedEffect(view) { if (cloudCooking) nav.step = detail!!.step }
    LaunchedEffect(view.key) {
        commands.collect { c ->
            val r = currentRecipe ?: return@collect
            val last = r.steps.lastIndex.coerceAtLeast(0)
            when (c) {
                is RecipeCommand.GoTo -> {
                    cooking = r.steps.isNotEmpty(); nav.step = c.step.coerceIn(0, last)
                    // A nuvem voltou ao passo (ex.: "fechar", "próximo passo"): fecha a lista que ela tinha aberto.
                    if (nav.panelFromCloud) { nav.panel = false; nav.panelFromCloud = false }
                }
                is RecipeCommand.Move -> { cooking = r.steps.isNotEmpty(); nav.step = (nav.step + c.delta).coerceIn(0, last) }
                RecipeCommand.ShowIngredients -> if (cooking) nav.panel = true else nav.tab = 0
                RecipeCommand.ShowSteps -> if (cooking) nav.panel = false else nav.tab = 1
                RecipeCommand.OpenIngredientsList -> { nav.panel = true; nav.panelFromCloud = true }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
            // Toques no fundo não fecham a receita (o fundo do modo Show volta à home).
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        AnimatedContent(
            targetState = when {
                view is RecipeView.GenericList -> Page.List
                recipe == null -> Page.Results
                cooking -> Page.Cooking
                else -> Page.Detail
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "recipe",
        ) { page ->
            when (page) {
                Page.List -> (view as? RecipeView.GenericList)?.let { l -> ListPage(l, onBack = onNavigateBack) }
                Page.Results -> (view as? RecipeView.Results)?.let { r ->
                    Results(r) { if (!onSelect(it)) local = it }
                }
                Page.Detail -> recipe?.let { r ->
                    Detail(
                        r, nav,
                        // Detalhe da nuvem: o "voltar" da cozinha devolve a lista; o local só fecha.
                        onBack = local?.let { { local = null } } ?: detail?.let { onNavigateBack },
                        actions = detail?.extraActions.orEmpty(),
                        onAction = onAction,
                        onCook = {
                            // "Iniciar receita": a nuvem responde com o passo a passo e a fala do passo 1.
                            detail?.startAction?.let { onAction(it.arguments) }
                            nav.step = 0
                            cooking = true
                        },
                    )
                }
                Page.Cooking -> recipe?.let { r ->
                    Cooking(
                        if (r.image == null) r.copy(image = imageFor(r.title)) else r, nav,
                        onStep = { if (cloudCooking) onStepSelected(r, it) },
                        onIngredientsClosed = { if (nav.panelFromCloud) { nav.panelFromCloud = false; onAction(BACK) } },
                        onClose = {
                            if (cloudCooking) onNavigateBack()
                            cooking = false
                        },
                    )
                }
            }
        }
    }
}

private val BACK = JsonArray(listOf(JsonPrimitive("back")))

private enum class Page { Results, Detail, Cooking, List }

/** Posição na receita, compartilhada entre toque e voz. */
private class RecipeNav {
    var step by mutableIntStateOf(0)
    var tab by mutableIntStateOf(-1)
    var panel by mutableStateOf(false)
    /** Painel aberto pela nuvem ("mostrar ingredientes"): ao fechar, a nuvem precisa voltar ao passo. */
    var panelFromCloud by mutableStateOf(false)
}

/** Espaço livre à direita do topo para o botão Home do modo Show. */
private val HomeButtonGutter = 84.dp
private val Accent = Palette.bright(Palette.Accent, 0.45f)
private val Tint = Ios.Tint
private val Card = Color(0xFF15131F)
private val CardRaised = Color(0xFF211D30)

// --- Lista -----------------------------------------------------------------------------------

@Composable
private fun Results(view: RecipeView.Results, onOpen: (Recipe) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Ambient(view.recipes.firstOrNull()?.image)
        Column(Modifier.fillMaxSize().padding(top = 18.dp, bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 32.dp, end = HomeButtonGutter),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Receitas")
                    Text(
                        view.title, color = Ios.Label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${view.recipes.size} ${if (view.recipes.size == 1) "receita" else "receitas"}",
                    color = Ios.SecondaryLabel, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(view.recipes, key = { i, r -> "$i-${r.title}" }) { _, r -> ResultCard(r) { onOpen(r) } }
            }
            Text(
                "Toque numa receita ou diga “Alexa, abrir a número 2”",
                color = Ios.TertiaryLabel, fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, top = 10.dp),
            )
        }
    }
}

@Composable
private fun ResultCard(r: Recipe, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(Card)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            RemoteImage(r.image, Modifier.fillMaxSize().background(CardRaised), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Card)))
            Ordinal(r.index + 1, Modifier.align(Alignment.TopStart).padding(10.dp))
            r.rating?.let { RatingPill(it, Modifier.align(Alignment.TopEnd).padding(10.dp)) }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                r.title, color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                r.time?.let { Meta(AppIcons.Timer, it) }
                r.difficulty?.let { Meta(AppIcons.Speed, it) }
            }
            Source(r)
        }
    }
}

// --- Detalhe ---------------------------------------------------------------------------------

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Detail(
    r: Recipe,
    nav: RecipeNav,
    onBack: (() -> Unit)?,
    actions: List<RecipeAction>,
    onAction: (JsonArray) -> Unit,
    onCook: () -> Unit,
) {
    if (nav.tab < 0) nav.tab = if (r.ingredients.isEmpty() && r.steps.isNotEmpty()) 1 else 0
    val tab = nav.tab
    val checked = remember(r) { mutableStateListOf<Int>() }
    Row(Modifier.fillMaxSize()) {
        // Foto com as informações por cima.
        Box(Modifier.fillMaxHeight().weight(0.42f)) {
            RemoteImage(r.image, Modifier.fillMaxSize().background(CardRaised), ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)),
                ),
            )
            // Funde a foto com o painel à direita.
            Box(Modifier.fillMaxHeight().width(48.dp).align(Alignment.CenterEnd).background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Background))))
            onBack?.let { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar à lista", it, Modifier.padding(16.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 40.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Source(r, light = true)
                Text(r.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                r.author?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.rating?.let { RatingPill(it, count = r.ratingCount) }
                    r.time?.let { InfoPill(AppIcons.Timer, it) }
                    r.servings?.let { InfoPill(AppIcons.People, it) }
                }
                // Botões do documento (salvar, lista de compras), com o rótulo que a Alexa mandou.
                if (actions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.take(3).forEach { a -> SecondaryButton(a.label, null) { onAction(a.arguments) } }
                    }
                }
            }
        }
        // Ingredientes e modo de preparo.
        Column(Modifier.fillMaxHeight().weight(0.58f).padding(top = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = HomeButtonGutter), verticalAlignment = Alignment.CenterVertically) {
                Tabs(
                    listOf("Ingredientes" to r.ingredients.count { !it.startsWith("# ") }, "Preparo" to r.steps.size),
                    tab, { nav.tab = it },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!r.hasDetail) {
                    Empty("Peça à Alexa: “abrir a receita ${r.title}”")
                } else if (tab == 0) {
                    Ingredients(r.ingredients, checked)
                } else {
                    Steps(r.steps)
                }
                if (r.steps.isNotEmpty()) {
                    PrimaryButton("Começar a cozinhar", Icons.Filled.PlayArrow, onCook, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun Ingredients(items: List<String>, checked: MutableList<Int>, bottomSpace: Dp = 76.dp) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 28.dp, top = 12.dp, bottom = bottomSpace)) {
        itemsIndexed(items) { i, line ->
            if (line.startsWith("# ")) {
                Eyebrow(line.removePrefix("# "), Modifier.padding(top = if (i == 0) 0.dp else 14.dp, bottom = 6.dp))
                return@itemsIndexed
            }
            val done = i in checked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { if (done) checked.remove(i) else checked.add(i) }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (done) Tint else Color.Transparent)
                        .border(2.dp, if (done) Tint else Ios.Gray, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (done) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                Text(
                    line, fontSize = 17.sp, lineHeight = 22.sp,
                    color = if (done) Ios.TertiaryLabel else Ios.Label,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 28.dp, top = 12.dp, bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(steps) { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.Primary), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text(step, color = Ios.Label, fontSize = 17.sp, lineHeight = 23.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// --- Passo a passo ---------------------------------------------------------------------------

@Composable
private fun Cooking(r: Recipe, nav: RecipeNav, onStep: (Int) -> Unit, onIngredientsClosed: () -> Unit, onClose: () -> Unit) {
    fun closeIngredients() { nav.panel = false; onIngredientsClosed() }
    val step = nav.step
    val showIngredients = nav.panel
    val checked = remember(r) { mutableStateListOf<Int>() }
    val last = r.steps.lastIndex
    fun go(delta: Int) {
        val next = (nav.step + delta).coerceIn(0, last)
        if (next == nav.step) return
        nav.step = next
        onStep(next)
    }

    Box(
        Modifier.fillMaxSize().pointerInput(r) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (abs(drag) > 80) go(if (drag < 0) 1 else -1) },
            ) { _, dx -> drag += dx }
        },
    ) {
        Ambient(r.image, strength = 0.86f)
        Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = HomeButtonGutter), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Filled.Close, "Sair do passo a passo", onClose)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Passo ${step + 1}", color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(" de ${r.steps.size}", color = Ios.SecondaryLabel, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Text(r.title, color = Ios.SecondaryLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
                    }
                    Progress(r.steps.size, step)
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Passo anterior", step > 0) { go(-1) }
                AnimatedContent(
                    step, Modifier.weight(1f).fillMaxHeight(), label = "step",
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it / 3 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 * dir } + fadeOut())
                    },
                ) { s ->
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
                        val text = r.steps.getOrNull(s).orEmpty()
                        Text(
                            text, color = Color.White, fontWeight = FontWeight.Medium,
                            fontSize = if (text.length > 220) 22.sp else 28.sp,
                            lineHeight = if (text.length > 220) 30.sp else 37.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                if (step < last) StepArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próximo passo", true) { go(1) }
                else StepArrow(Icons.Filled.Check, "Terminar", true, highlight = true, onClick = onClose)
            }
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.ingredients.isNotEmpty()) {
                    SecondaryButton("Ingredientes", Icons.AutoMirrored.Filled.List) { if (nav.panel) closeIngredients() else nav.panel = true }
                }
                Spacer(Modifier.weight(1f))
                Text("Deslize ou diga “Alexa, próximo passo”", color = Ios.TertiaryLabel, fontSize = 13.sp)
            }
        }
        // Painel lateral com os ingredientes, para conferir sem sair do passo.
        AnimatedVisibility(
            showIngredients, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxHeight().width(320.dp)
                    .background(CardRaised.copy(alpha = 0.97f), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .padding(top = 18.dp),
            ) {
                Row(Modifier.padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ingredientes", color = Ios.Label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundIcon(Icons.Filled.Close, "Fechar ingredientes", { closeIngredients() })
                }
                Ingredients(r.ingredients, checked, bottomSpace = 16.dp)
            }
        }
    }
}

@Composable
private fun Progress(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().height(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count.coerceAtLeast(1)) { i ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .background(if (i <= current) Tint else Color.White.copy(alpha = 0.16f)),
            )
        }
    }
}

@Composable
private fun StepArrow(icon: ImageVector, description: String, enabled: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.width(96.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (enabled) {
            Box(
                Modifier.size(60.dp).clip(CircleShape)
                    .background(if (highlight) Tint else Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, description, tint = if (highlight) Color.Black else Color.White, modifier = Modifier.size(34.dp)) }
        }
    }
}

// --- Peças -----------------------------------------------------------------------------------

/** Fundo: a foto da receita desfocada e escurecida (o desfoque só existe no Android 12+). */
@Composable
private fun BoxScope.Ambient(image: String?, strength: Float = 0.8f) {
    if (image != null) {
        RemoteImage(
            image,
            Modifier.matchParentSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier),
            ContentScale.Crop,
        )
    }
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(Palette.Background.copy(alpha = strength), Palette.Background.copy(alpha = (strength + 0.12f).coerceAtMost(1f)))),
        ),
    )
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = modifier)
}

@Composable
private fun Ordinal(n: Int, modifier: Modifier) {
    Box(
        modifier.size(30.dp).clip(CircleShape).background(Palette.Primary).border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun RatingPill(rating: Double, modifier: Modifier = Modifier, count: String? = null) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.size(14.dp))
        Text(String.format(Locale("pt", "BR"), "%.1f", rating), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        count?.let { Text("($it)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
    }
}

@Composable
private fun InfoPill(icon: ImageVector, text: String) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(text, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = Ios.SecondaryLabel, modifier = Modifier.size(14.dp))
        Text(text, color = Ios.SecondaryLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Source(r: Recipe, light: Boolean = false) {
    if (r.source == null && r.sourceLogo == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        r.sourceLogo?.let { RemoteImage(it, Modifier.height(16.dp).width(40.dp), ContentScale.Fit) }
        r.source?.let {
            Text(it, color = if (light) Color.White.copy(alpha = 0.8f) else Ios.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tabs(tabs: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { i, (label, count) ->
            val on = i == selected
            Row(
                Modifier.clip(CircleShape).background(if (on) Palette.Primary else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, color = if (on) Color.White else Ios.SecondaryLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (count > 0) Text("$count", color = if (on) Color.White.copy(alpha = 0.7f) else Ios.TertiaryLabel, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Palette.bright(Palette.Primary, 0.15f), Palette.bright(Palette.Accent, 0.1f))))
            .clickable(onClick = onClick).padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ios.SecondaryLabel, fontSize = 16.sp)
    }
}

// --- Lista avulsa (genericListViewModel) -----------------------------------------------------

/** Lista da cozinha fora do passo a passo: itens grandes para marcar, em duas colunas. */
@Composable
private fun ListPage(list: RecipeView.GenericList, onBack: () -> Unit) {
    val checked = remember(list.key) { mutableStateListOf<Int>() }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Palette.Primary.copy(alpha = 0.35f), Palette.Background, Palette.Accent.copy(alpha = 0.18f))),
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = HomeButtonGutter), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onBack)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (list.isIngredients) "Para separar" else "Receita")
                    Text(list.title, color = Ios.Label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${checked.size} de ${list.items.size}", color = Ios.SecondaryLabel, fontSize = 14.sp)
                list.logo?.let { RemoteImage(it, Modifier.padding(start = 14.dp).height(22.dp).width(64.dp), ContentScale.Fit) }
            }
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItemsIndexed(list.items) { i, item ->
                    val done = i in checked
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (done) Card else CardRaised)
                            .border(1.dp, if (done) Color.Transparent else Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                            .clickable { if (done) checked.remove(i) else checked.add(i) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape)
                                .background(if (done) Tint else if (list.numbered) Palette.Primary else Color.Transparent)
                                .border(2.dp, if (done) Tint else if (list.numbered) Palette.Primary else Ios.Gray, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                done -> Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                list.numbered -> Text("${i + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            item, fontSize = 17.sp, lineHeight = 22.sp,
                            color = if (done) Ios.TertiaryLabel else Ios.Label,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
    }
}
