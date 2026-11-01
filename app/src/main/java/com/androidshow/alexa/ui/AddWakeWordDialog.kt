package com.androidshow.alexa.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidshow.alexa.graph
import com.androidshow.alexa.ui.ios.Ios
import com.androidshow.alexa.voice.wake.CustomWakeWord
import com.androidshow.alexa.voice.wake.CustomWakeWords
import com.androidshow.alexa.voice.wake.wakeWordTrainingPrompt
import kotlinx.coroutines.launch

/**
 * Modal "Adicionar nova palavra": nome + arquivo .onnx do openWakeWord, validado antes de entrar
 * na lista. Traz um prompt para copiar e pedir a uma IA o passo a passo do treino.
 */
@Composable
fun AddWakeWordDialog(onDismiss: () -> Unit, onAdded: (CustomWakeWord) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // .onnx não tem tipo MIME padrão: o seletor aceita tudo e a validação confere o conteúdo.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri
            fileName = displayName(context, uri)
            error = null
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Ios.Group)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Adicionar nova palavra", color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Envie o modelo da palavra: um arquivo .onnx treinado com o openWakeWord, no mesmo formato do " +
                    "modelo “Alexa” que já vem no app.\n\n" +
                    "• Entrada [1, N, 96] e saída com uma probabilidade [1, 1]\n" +
                    "• Só o classificador da palavra; o espectrograma e o embedding já estão no app\n" +
                    "• Normalmente tem algumas centenas de KB (máximo 10 MB)",
                color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 19.sp,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(MAX_LABEL); error = null },
                label = { Text("Nome da palavra") },
                placeholder = { Text("Ex.: Jarvis") },
                supportingText = { Text("É o que aparece no app e na notificação. Use a palavra que o modelo reconhece.") },
                singleLine = true,
                enabled = !busy,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, if (file == null) Ios.Separator else Ios.Tint, RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { pick.launch(arrayOf("*/*")) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Upload, null, tint = Ios.Tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fileName ?: "Escolher arquivo do modelo", color = Ios.Label, fontSize = 16.sp, maxLines = 1)
                    Text(if (file == null) "Arquivo .onnx" else "Toque para trocar", color = Ios.SecondaryLabel, fontSize = 13.sp)
                }
            }

            error?.let { Text(it, color = Ios.Destructive, fontSize = 14.sp) }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ios.GroupPressed)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Dúvidas em como criar o modelo?", color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Copie este prompt e cole numa IA, como o ChatGPT ou o Claude. Ele traz o formato que o app espera, " +
                        "as etapas do treino, os requisitos e o custo de processamento.",
                    color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 19.sp,
                )
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("prompt", wakeWordTrainingPrompt(label)))
                        Toast.makeText(context, "Prompt copiado", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(AppIcons.Copy, null, tint = Ios.Tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar prompt", color = Ios.Tint, fontSize = 15.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar", color = Ios.Tint) }
                Spacer(Modifier.width(4.dp))
                val ready = label.isNotBlank() && file != null && !busy
                TextButton(
                    enabled = ready,
                    onClick = {
                        val uri = file ?: return@TextButton
                        busy = true
                        scope.launch {
                            context.graph.customWakeWords.import(uri, label)
                                .onSuccess(onAdded)
                                .onFailure {
                                    error = (it as? CustomWakeWords.InvalidModel)?.message ?: "Não deu para importar o modelo."
                                }
                            busy = false
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Ios.Tint, strokeWidth = 2.dp)
                    else Text("Adicionar", color = if (ready) Ios.Tint else Ios.TertiaryLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ios.Label,
    unfocusedTextColor = Ios.Label,
    disabledTextColor = Ios.SecondaryLabel,
    focusedBorderColor = Ios.Tint,
    unfocusedBorderColor = Ios.Separator,
    focusedLabelColor = Ios.Tint,
    unfocusedLabelColor = Ios.SecondaryLabel,
    cursorColor = Ios.Tint,
    focusedPlaceholderColor = Ios.TertiaryLabel,
    unfocusedPlaceholderColor = Ios.TertiaryLabel,
    focusedSupportingTextColor = Ios.SecondaryLabel,
    unfocusedSupportingTextColor = Ios.SecondaryLabel,
)

private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

private const val MAX_LABEL = 24
