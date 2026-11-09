package com.tabslify.tabs.school.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.ErrorSoft
import com.tabslify.core.ui.SuccessGreen
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary

@Composable
fun StudyResultScreen(
    state: StudySessionUiState,
    projectTitle: String,
    nextDay: StudyPlanDayDto?,
    onNextDay: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_result),
            subtitle = projectTitle.ifBlank { null },
            onBack = onBack,
            paddingValues = paddingValues,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgCard)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.study_result_score, state.scorePercent),
                        color = if (state.scorePercent >= 70) SuccessGreen else ErrorSoft,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.study_plan_done_short, state.answers.size, state.total),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            if (state.weak.isNotEmpty()) {
                item { StudySectionTitle(stringResource(R.string.study_weak_items)) }
                item {
                    Text(
                        text = stringResource(R.string.study_back_tomorrow),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                items(state.weak, key = { it.task.assignment.id }) { answer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgSurface)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = answer.task.item.front,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            answer.task.item.back?.takeIf { it.isNotBlank() }?.let { back ->
                                Text(
                                    text = stringResource(R.string.study_expected_answer, back),
                                    color = TextTertiary,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        StudySourceChip(answer.task.item.referenceLabel)
                    }
                }
            }

            item {
                if (nextDay != null) {
                    StudyPrimaryButton(
                        text = stringResource(R.string.study_next_day, nextDay.dayIndex),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNextDay,
                    )
                } else {
                    StudyPrimaryButton(
                        text = stringResource(R.string.study_finish),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onFinish,
                    )
                }
            }

            item {
                StudySecondaryButton(
                    text = stringResource(R.string.study_back_to_plan),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBack,
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
