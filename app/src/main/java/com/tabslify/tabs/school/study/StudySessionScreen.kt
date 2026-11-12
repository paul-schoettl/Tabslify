package com.tabslify.tabs.school.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.ui.AccentFill
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.ErrorSoft
import com.tabslify.core.ui.OnAccentFill
import com.tabslify.core.ui.SuccessGreen
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary

@Composable
fun StudySessionScreen(
    state: StudySessionUiState,
    onSubmit: (String) -> Unit,
    onDontKnow: () -> Unit,
    onSelfGrade: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    val task = state.current
    var answer by remember(state.index) { mutableStateOf("") }

    LaunchedEffect(state.index) {
        answer = ""
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_session),
            subtitle = stringResource(
                R.string.study_question_progress,
                (state.index + 1).coerceAtMost(state.total.coerceAtLeast(1)),
                state.total.coerceAtLeast(1),
            ),
            onBack = onBack,
            paddingValues = paddingValues,
        )

        if (task == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.study_no_tasks_today), color = TextSecondary)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgCard)
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StudyTypeChip(studyKindLabel(task.item.kind))
                        StudySourceChip(task.item.referenceLabel)
                    }
                    Text(
                        text = stringResource(R.string.study_question),
                        color = TextTertiary,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = if (task.item.kind == "vocab") {
                            stringResource(R.string.study_vocab_question, task.item.front)
                        } else {
                            stringResource(R.string.study_translation_hint, task.item.front)
                        },
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (task.item.kind != "vocab" && !task.item.context.isNullOrBlank()) {
                        Text(
                            text = task.item.context.orEmpty(),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val last = state.lastAnswer
            if (last != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BgSurface)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (last.correct) {
                            stringResource(R.string.study_correct)
                        } else {
                            stringResource(R.string.study_wrong)
                        },
                        color = if (last.correct) SuccessGreen else ErrorSoft,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!last.correct && !task.item.back.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.study_expected_answer, task.item.back.orEmpty()),
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    if (last.answer.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.study_your_answer, last.answer),
                            color = TextTertiary,
                            fontSize = 12.sp,
                        )
                    }
                }
                StudyPrimaryButton(
                    text = stringResource(R.string.weiter),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNext,
                )
            } else if (state.awaitingSelfGrade) {
                Text(
                    text = stringResource(R.string.study_self_grade),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudyPrimaryButton(
                        text = stringResource(R.string.study_correct),
                        modifier = Modifier.weight(1f),
                        onClick = { onSelfGrade(true) },
                    )
                    StudySecondaryButton(
                        text = stringResource(R.string.study_wrong),
                        modifier = Modifier.weight(1f),
                        onClick = { onSelfGrade(false) },
                    )
                }
            } else {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text(stringResource(R.string.study_answer)) },
                    placeholder = { Text(stringResource(R.string.study_answer_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(answer) }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudyPrimaryButton(
                        text = stringResource(R.string.study_show_answer),
                        modifier = Modifier.weight(1f),
                        enabled = answer.isNotBlank(),
                        onClick = { onSubmit(answer) },
                    )
                    StudySecondaryButton(
                        text = stringResource(R.string.study_dont_know),
                        modifier = Modifier.weight(1f),
                        onClick = onDontKnow,
                    )
                }
            }

            if (state.lastAnswer == null && !state.awaitingSelfGrade && !task.item.back.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.study_source) + ": " + task.item.referenceLabel,
                    color = TextTertiary,
                    fontSize = 10.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
