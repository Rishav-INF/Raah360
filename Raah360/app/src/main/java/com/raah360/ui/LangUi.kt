package com.raah360.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raah360.core.I18n
import com.raah360.core.Lang

/** The visitor's language, provided once at the top of the app. */
val LocalLang = staticCompositionLocalOf { Lang.EN }

/** Translated text for [key] in the current language. */
@Composable
@ReadOnlyComposable
fun tr(key: String, vararg args: Pair<String, Any>): String = I18n.t(LocalLang.current, key, *args)

/** Pick the app language; the voice follows it. Names are shown in their own script. */
@Composable
fun LanguageDialog(current: Lang, onPick: (Lang) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("language") + if (current != Lang.EN) " · Language" else "") },
        text = {
            Column {
                Lang.entries.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(l) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = l == current, onClick = { onPick(l) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(l.nativeName, style = MaterialTheme.typography.titleMedium, color = R360.Text)
                            if (l != Lang.EN) Text(l.englishName, style = MaterialTheme.typography.labelMedium, color = R360.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        containerColor = R360.Surface,
    )
}
