package com.tabslify.tabs.school.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.ui.AccentFill
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.OnAccentFill
import com.tabslify.core.ui.OnAccentFillMuted
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import java.time.LocalDate

@Composable
fun StudyPlanScreen(
    state: StudyPlanUiState,
    onNewPlan: () -> Unit,
    onGeneratePlan: () -> Unit,
    onOpenDay: (StudyPlanDayDto) -> Unit,
    onOpenMaterials: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_plan),
            subtitle = state.project?.title?.ifBlank { null },
            onBack = onBack,
            paddingValues = paddingValues,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgSurface)
                        .clickable { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text("↻", color = TextSecondary, fontSize = 15.sp)
                }
            },
        )

        when {
            state.loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextPrimary)
                }
            }

            state.project == null -> {
                StudyEmptyState(
                    title = stringResource(R.string.study_plan_empty_title),
                    text = stringResource(R.string.study_plan_empty_text),
                    actionText = stringResource(R.string.study_plan_new),
                    onAction = onNewPlan,
                )
            }

            state.plan == null -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = state.project?.title?.ifBlank { null }
                                ?: stringResource(R.string.study_untitled),
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    item { StudyErrorBanner(state.error) }
                    item {
                        Text(
                            text = stringResource(R.string.study_plan_review_hint),
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    item {
                        StudyPrimaryButton(
                            text = if (state.busy) {
                                stringResource(R.string.study_plan_generating)
                            } else {
                                stringResource(R.string.study_plan_generate)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.items.isNotEmpty() && !state.busy,
                            onClick = onGeneratePlan,
                        )
                    }
                    item {
                        StudySecondaryButton(
                            text = stringResource(R.string.study_plan_new),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onNewPlan,
                        )
                    }
                }
            }

            else -> {
                val today = LocalDate.now().toString()
                val currentDay = state.days.firstOrNull { it.status != "done" }?.dayIndex
                    ?: state.days.size.coerceAtLeast(1)
                val totalDays = state.days.size.takeIf { it > 0 } ?: 5
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.offline) {
                        item { StudyNoticeBanner(stringResource(R.string.study_plan_offline)) }
                    }
                    item { StudyErrorBanner(state.error) }

                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(BgCard)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.study_plan_progress,
                                    currentDay,
                                    totalDays,
                                ),
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            StudyProgressBar(done = state.doneCount, total = state.totalCount)
                            if (state.pendingTodayCount > 0) {
                                StudyPrimaryButton(
                                    text = stringResource(R.string.study_start_session) + " · " + state.pendingTodayCount,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        state.todayDay()?.let(onOpenDay)
                                    },
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.study_no_tasks_today),
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    item { StudySectionTitle(stringResource(R.string.study_tasks)) }

                    items(state.days, key = { it.id }) { day ->
                        val tasks = state.tasksFor(day)
                        val done = tasks.count { it.assignment.status == "done" }
                        StudyDayCard(
                            day = day,
                            taskCount = tasks.size,
                            doneCount = done,
                            isToday = day.date == today,
                            onClick = { onOpenDay(day) },
                        )
                    }

                    item {
                        StudySecondaryButton(
                            text = stringResource(R.string.study_material),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenMaterials,
                        )
                    }
                    item {
                        StudySecondaryButton(
                            text = stringResource(R.string.study_plan_new),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onNewPlan,
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StudyDayCard(
    day: StudyPlanDayDto,
    taskCount: Int,
    doneCount: Int,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isToday) AccentFill else BgCard
    val titleColor = if (isToday) OnAccentFill else TextPrimary
    val secondaryColor = if (isToday) OnAccentFillMuted else TextSecondary
    val tertiaryColor = if (isToday) OnAccentFillMuted else TextTertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isToday) Color.White.copy(alpha = 0.18f) else BgSurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.dayIndex.toString(),
                color = titleColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.study_plan_day, day.dayIndex),
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = listOfNotNull(day.date, day.focus.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = secondaryColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$doneCount/$taskCount",
                color = titleColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (day.status == "done") {
                    stringResource(R.string.study_done)
                } else {
                    stringResource(R.string.study_task_count, taskCount)
                },
                color = tertiaryColor,
                fontSize = 10.sp,
            )
        }
    }
}
