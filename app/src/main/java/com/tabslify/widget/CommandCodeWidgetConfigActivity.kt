package com.tabslify.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.ui.AccentViolet
import com.tabslify.core.ui.BgDeep
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CommandCodeWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )

        setContent {
            CommandCodeConfigScreen(
                initialCookie = CommandCodeUsageRepository.readCookie(this),
                onSave = { cookie ->
                    if (cookie.isBlank()) {
                        Toast.makeText(this, getString(R.string.commandcode_config_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        CommandCodeUsageRepository.writeCookie(this, cookie)
                        CommandCodeUsageWidget.updateAll(this)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        )
                        finish()
                    }
                },
                onCancel = { finish() }
            )
        }
    }
}

@Composable
private fun CommandCodeConfigScreen(
    initialCookie: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cookie by remember { mutableStateOf(initialCookie) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = context.getString(R.string.commandcode_config_title),
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = context.getString(R.string.commandcode_config_help),
            color = TextSecondary,
            fontSize = 13.sp
        )
        OutlinedTextField(
            value = cookie,
            onValueChange = {
                cookie = it
                status = null
            },
            label = { Text(context.getString(R.string.commandcode_config_hint)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentViolet,
                unfocusedBorderColor = TextSecondary,
                cursorColor = AccentViolet,
                focusedLabelColor = AccentViolet,
                unfocusedLabelColor = TextSecondary
            )
        )

        status?.let {
            Text(text = it, color = TextSecondary, fontSize = 13.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onSave(cookie) }) {
                Text(context.getString(R.string.commandcode_config_save))
            }
            TextButton(
                onClick = {
                    if (cookie.isBlank()) return@TextButton
                    testing = true
                    status = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { CommandCodeUsageRepository.load(context) }
                        }
                        status = result.fold(
                            onSuccess = { usage ->
                                context.getString(
                                    R.string.commandcode_config_ok,
                                    String.format(Locale.US, "\$%.2f", usage.used),
                                    String.format(Locale.US, "\$%.2f", usage.cap),
                                    String.format(Locale.US, "%.1f%%", usage.pct)
                                )
                            },
                            onFailure = { error ->
                                context.getString(
                                    R.string.commandcode_config_failed,
                                    error.message ?: error.javaClass.simpleName
                                )
                            }
                        )
                        testing = false
                    }
                },
                enabled = !testing
            ) {
                Text(
                    text = context.getString(
                        if (testing) R.string.commandcode_config_testing else R.string.commandcode_config_test
                    ),
                    color = AccentViolet
                )
            }
            if (testing) {
                CircularProgressIndicator(color = AccentViolet, strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(context.getString(R.string.commandcode_config_cancel), color = TextSecondary)
            }
        }
    }
}
