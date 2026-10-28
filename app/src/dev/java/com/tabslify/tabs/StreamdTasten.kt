package com.tabslify.tabs

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.ui.TextPrimary

private data class TastenEintrag(
    val label: String,
    val folge: List<Pair<Int, Boolean>>
)

private fun einzeln(code: Int): List<Pair<Int, Boolean>> =
    listOf(code to true, code to false)

private fun mitModifikator(modifikator: Int, code: Int): List<Pair<Int, Boolean>> =
    listOf(
        modifikator to true,
        code to true,
        code to false,
        modifikator to false
    )

private fun mitZweiModifikatoren(
    erster: Int,
    zweiter: Int,
    code: Int
): List<Pair<Int, Boolean>> = listOf(
    erster to true,
    zweiter to true,
    code to true,
    code to false,
    zweiter to false,
    erster to false
)

@Composable
fun StreamdTastenDialog(
    onSchliessen: () -> Unit,
    onFolge: (List<Pair<Int, Boolean>>) -> Unit
) {
    val strg = KeyEvent.KEYCODE_CTRL_LEFT
    val alt = KeyEvent.KEYCODE_ALT_LEFT
    val meta = KeyEvent.KEYCODE_META_LEFT
    val eintraege = listOf(
        TastenEintrag("Esc", einzeln(KeyEvent.KEYCODE_ESCAPE)),
        TastenEintrag("Tab", einzeln(KeyEvent.KEYCODE_TAB)),
        TastenEintrag("Enter", einzeln(KeyEvent.KEYCODE_ENTER)),
        TastenEintrag("⌫", einzeln(KeyEvent.KEYCODE_DEL)),
        TastenEintrag("Entf", einzeln(KeyEvent.KEYCODE_FORWARD_DEL)),
        TastenEintrag("↑", einzeln(KeyEvent.KEYCODE_DPAD_UP)),
        TastenEintrag("↓", einzeln(KeyEvent.KEYCODE_DPAD_DOWN)),
        TastenEintrag("←", einzeln(KeyEvent.KEYCODE_DPAD_LEFT)),
        TastenEintrag("→", einzeln(KeyEvent.KEYCODE_DPAD_RIGHT)),
        TastenEintrag("Home", einzeln(KeyEvent.KEYCODE_MOVE_HOME)),
        TastenEintrag("End", einzeln(KeyEvent.KEYCODE_MOVE_END)),
        TastenEintrag("PgUp", einzeln(KeyEvent.KEYCODE_PAGE_UP)),
        TastenEintrag("PgDn", einzeln(KeyEvent.KEYCODE_PAGE_DOWN)),
        TastenEintrag("F1", einzeln(KeyEvent.KEYCODE_F1)),
        TastenEintrag("F2", einzeln(KeyEvent.KEYCODE_F2)),
        TastenEintrag("F3", einzeln(KeyEvent.KEYCODE_F3)),
        TastenEintrag("F4", einzeln(KeyEvent.KEYCODE_F4)),
        TastenEintrag("F5", einzeln(KeyEvent.KEYCODE_F5)),
        TastenEintrag("F6", einzeln(KeyEvent.KEYCODE_F6)),
        TastenEintrag("F7", einzeln(KeyEvent.KEYCODE_F7)),
        TastenEintrag("F8", einzeln(KeyEvent.KEYCODE_F8)),
        TastenEintrag("F9", einzeln(KeyEvent.KEYCODE_F9)),
        TastenEintrag("F10", einzeln(KeyEvent.KEYCODE_F10)),
        TastenEintrag("F11", einzeln(KeyEvent.KEYCODE_F11)),
        TastenEintrag("F12", einzeln(KeyEvent.KEYCODE_F12)),
        TastenEintrag(
            "Strg+Alt+Entf",
            mitZweiModifikatoren(strg, alt, KeyEvent.KEYCODE_FORWARD_DEL)
        ),
        TastenEintrag("Alt+Tab", mitModifikator(alt, KeyEvent.KEYCODE_TAB)),
        TastenEintrag("Alt+F2", mitModifikator(alt, KeyEvent.KEYCODE_F2)),
        TastenEintrag("Alt+F4", mitModifikator(alt, KeyEvent.KEYCODE_F4)),
        TastenEintrag("Super", einzeln(meta)),
        TastenEintrag("Strg+C", mitModifikator(strg, KeyEvent.KEYCODE_C)),
        TastenEintrag("Strg+V", mitModifikator(strg, KeyEvent.KEYCODE_V)),
        TastenEintrag("Strg+X", mitModifikator(strg, KeyEvent.KEYCODE_X)),
        TastenEintrag("Strg+Z", mitModifikator(strg, KeyEvent.KEYCODE_Z)),
        TastenEintrag("Win+L", mitModifikator(meta, KeyEvent.KEYCODE_L)),
        TastenEintrag("Druck", einzeln(KeyEvent.KEYCODE_SYSRQ))
    )

    AlertDialog(
        onDismissRequest = onSchliessen,
        title = { Text(stringResource(R.string.stream_tasten)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                eintraege.chunked(3).forEach { zeile ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        zeile.forEach { eintrag ->
                            Button(
                                onClick = { onFolge(eintrag.folge) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(
                                    horizontal = 2.dp,
                                    vertical = 6.dp
                                )
                            ) {
                                Text(
                                    text = eintrag.label,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        repeat(3 - zeile.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSchliessen) {
                Text(stringResource(R.string.abbrechen))
            }
        }
    )
}
