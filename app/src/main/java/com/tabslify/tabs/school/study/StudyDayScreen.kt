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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun StudyDayScreen(
    day: StudyPlanDayDto,
    state: StudyPlanUiState,
    onStartSession: (List<StudySessionTask>) -> Unit,
    onMarkDone: (String) -> Unit,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    val tasks = state.tasksFor(day)
    val pending = tasks.filter { it.assignment.status != "done" }
    val done = tasks.size - pending.size

    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_plan_day, day.dayIndex),
            subtitle = listOfNotNull(day.date, day.focus.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .ifBlank { null },
            onBack = onBack,
            paddingValues = paddingValues,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BgCard)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudyProgressBar(done = done, total = tasks.size)
                    if (pending.isNotEmpty()) {
                        StudyPrimaryButton(
                            text = stringResource(R.string.study_start_session) + " · " + pending.size,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onStartSession(pending) },
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

            items(tasks, key = { it.assignment.id }) { task ->
                StudyTaskRow(
                    task = task,
                    onMarkDone = { onMarkDone(task.assignment.id) },
                )
            }

            if (tasks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.study_no_tasks_today),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StudyTaskRow(
    task: StudySessionTask,
    onMarkDone: () -> Unit,
) {
    val done = task.assignment.status == "done"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (done) AccentFill else BgSurface)
                .clickable(enabled = !done, onClick = onMarkDone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (done) "✓" else "",
                color = if (done) OnAccentFill else TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = task.item.front,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudyTypeChip(StudyKindLabel(task.item.kind))
                StudySourceChip(task.item.referenceLabel)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.study_minutes_short, task.assignment.plannedMinutes),
                color = TextTertiary,
                fontSize = 10.sp,
            )
            if (done) {
                Text(
                    text = stringResource(R.string.study_done),
                    color = OnAccentFillMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
