package com.androidshow.alexa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidshow.alexa.auth.Marketplace
import com.androidshow.alexa.ui.ios.Ios
import com.androidshow.alexa.ui.ios.IosFilledButton
import com.androidshow.alexa.ui.ios.IosPage
import com.androidshow.alexa.ui.ios.IosRow
import com.androidshow.alexa.ui.ios.section

@Composable
fun LoginScreen(
    marketplace: Marketplace,
    onPickMarketplace: () -> Unit,
    onLogin: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    IosPage("AndroidShow") {
        item {
            Text(
                "Entre com sua conta Amazon para registrar este aparelho como dispositivo Alexa.",
                color = Ios.SecondaryLabel, fontSize = 15.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        section {
            IosRow("Loja da conta", icon = AppIcons.Globe, iconColor = Palette.Secondary, value = marketplace.label, onClick = onPickMarketplace)
        }
        item { Box(Modifier.padding(top = 28.dp)) { IosFilledButton("Entrar com Amazon", onLogin) } }
        section {
            IosRow("Copiar diagnóstico", icon = AppIcons.Copy, iconColor = Ios.Gray, chevron = false, onClick = onCopyDiagnostics)
        }
    }
}

@Composable
fun MarketplaceScreen(selected: Marketplace, onSelect: (Marketplace) -> Unit, onBack: () -> Unit) {
    IosPage("Loja da conta", back = "Voltar", onBack = onBack) {
        section(footer = "A loja define o idioma e a região da Alexa.") {
            Marketplace.entries.forEach { m ->
                IosRow(m.label, subtitle = m.domain, onClick = { onSelect(m) }, trailing = {
                    if (m == selected) Icon(Icons.Filled.Check, null, tint = Ios.Tint)
                })
            }
        }
    }
}
