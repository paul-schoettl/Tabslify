package com.tabslify.tabs.fitnesstab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tabslify.R
import com.tabslify.core.ui.Accent
import com.tabslify.core.ui.AccentSoft
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.PloppingButton
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary

@Composable
fun PushUpEditDialog(vm: FitnessViewModel) {
    val editor = vm.pushUpEditor ?: return
    var reps by remember(editor.sessionId) { mutableIntStateOf(editor.originalReps) }
    var manual by remember(editor.sessionId) { mutableStateOf(editor.originalReps.toString()) }
    var repTimes by remember(editor.sessionId) { mutableStateOf(editor.repTimesMs) }

    Dialog(
        onDismissRequest = { vm.dismissPushUpEditor() },
        properties = DialogProperties(dismissOnClickOutside = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(BgSurface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.fitness_result_title),
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.fitness_result_detected,
                    editor.originalReps,
                    formatDuration(editor.durationMs)
                ),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StepButton(
                    icon = Icons.Default.Remove,
                    description = stringResource(R.string.fitness_result_decrease),
                    enabled = reps > 0,
                    onClick = {
                        if (reps > 0) {
                            reps--
                            if (repTimes.isNotEmpty()) {
                                repTimes = repTimes.dropLast(1)
                            }
                            manual = reps.toString()
                        }
                    }
                )
                Text(
                    text = reps.toString(),
                    color = TextPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black
                )
                StepButton(
                    icon = Icons.Default.Add,
                    description = stringResource(R.string.fitness_result_increase),
                    enabled = reps < 9999,
                    onClick = {
                        reps++
                        manual = reps.toString()
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.fitness_result_hint),
                color = TextTertiary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = manual,
                onValueChange = { raw ->
                    val cleaned = raw.filter { it.isDigit() }.take(4)
                    manual = cleaned
                    val value = cleaned.toIntOrNull() ?: 0
                    reps = value.coerceIn(0, 9999)
                    if (repTimes.size > reps) {
                        repTimes = repTimes.take(reps)
                    }
                },
                label = {
                    Text(
                        text = stringResource(R.string.fitness_result_manual),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgCard,
                    focusedContainerColor = BgCard,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color(0x40FFFFFF),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                ),
                shape = RoundedCornerShape(12.dp)
            )

            if (repTimes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.fitness_result_rep_list),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repTimes.forEachIndexed { index, time ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgCard)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.fitness_result_rep_number, index + 1),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatDuration(time),
                                color = TextTertiary,
                                fontSize = 11.sp
                            )
                            IconButton(
                                onClick = {
                                    repTimes = repTimes.filterIndexed { i, _ -> i != index }
                                    reps = (reps - 1).coerceAtLeast(0)
                                    manual = reps.toString()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.fitness_result_delete_rep),
                                    tint = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PloppingButton(
                    onClick = { vm.dismissPushUpEditor() },
                    colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.fitness_result_cancel),
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                PloppingButton(
                    onClick = { vm.applyPushUpEdit(reps, repTimes) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.fitness_result_apply),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) AccentSoft else BgCard)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) TextPrimary else TextTertiary
        )
    }
}
