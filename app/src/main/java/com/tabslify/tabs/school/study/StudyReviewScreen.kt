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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary

@Composable
fun StudyReviewScreen(
    state: StudyPlanUiState,
    onUpdateItem: (StudyItemDto) -> Unit,
    onDeleteItem: (String) -> Unit,
    onAddItem: (String, String, String?) -> Unit,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    var editing by remember { mutableStateOf<StudyItemDto?>(null) }
    var deleting by remember { mutableStateOf<StudyItemDto?>(null) }
    var adding by remember { mutableStateOf(false) }

    val vocab = state.items.filter { it.kind == "vocab" }
    val texts = state.items.filter { it.kind != "vocab" }

    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_review_title),
            subtitle = state.project?.title?.ifBlank {
                stringResource(R.string.study_review_subtitle)
            } ?: stringResource(R.string.study_review_subtitle),
            onBack = onBack,
            paddingValues = paddingValues,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StudyNoticeBanner(stringResource(R.string.study_review_instruction)) }
            item { StudyErrorBanner(state.error) }

            if (state.items.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.study_no_items),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            if (vocab.isNotEmpty()) {
                item { StudySectionTitle(stringResource(R.string.study_items_vocab)) }
                items(vocab, key = { it.id }) { item ->
                    StudyItemCard(
                        item = item,
                        onEdit = { editing = item },
                        onDelete = { deleting = item },
                    )
                }
            }

            if (texts.isNotEmpty()) {
                item { StudySectionTitle(stringResource(R.string.study_items_text)) }
                items(texts, key = { it.id }) { item ->
                    StudyItemCard(
                        item = item,
                        onEdit = { editing = item },
                        onDelete = { deleting = item },
                    )
                }
            }

            item {
                StudySecondaryButton(
                    text = stringResource(R.string.study_add_item),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { adding = true },
                )
            }

            state.project?.let { project ->
                item {
                    StudyPrimaryButton(
                        text = if (state.busy) {
                            stringResource(R.string.study_plan_generating)
                        } else {
                            stringResource(R.string.study_plan_generate)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.items.isNotEmpty() && !state.busy,
                        onClick = onGenerate,
                    )
                }
                item {
                    Text(
                        text = project.subject.ifBlank { stringResource(R.string.study_untitled) },
                        color = TextTertiary,
                        fontSize = 11.sp,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { item ->
        StudyItemEditDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { updated ->
                onUpdateItem(updated)
                editing = null
            },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = BgSurface,
            title = { Text(stringResource(R.string.study_delete_item), color = TextPrimary) },
            text = {
                Text(
                    text = item.front.take(120),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteItem(item.id)
                    deleting = null
                }) {
                    Text(stringResource(R.string.loschen), color = ErrorSoft)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.abbrechen), color = TextSecondary)
                }
            },
        )
    }

    if (adding) {
        StudyItemAddDialog(
            onDismiss = { adding = false },
            onSave = { kind, front, back ->
                onAddItem(kind, front, back)
                adding = false
            },
        )
    }
}

@Composable
private fun StudyItemCard(
    item: StudyItemDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudyTypeChip(studyKindLabel(item.kind))
            StudySourceChip(item.referenceLabel)
            if (item.confidence < 0.6f) StudyConfidenceWarning()
        }
        Text(
            text = item.front,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        item.back?.takeIf { it.isNotBlank() }?.let { back ->
            Text(
                text = back,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface)
                    .clickable { onEdit() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.study_edit_item), color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface)
                    .clickable { onDelete() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.loschen), color = ErrorSoft, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StudyItemEditDialog(
    item: StudyItemDto,
    onDismiss: () -> Unit,
    onSave: (StudyItemDto) -> Unit,
) {
    var front by remember { mutableStateOf(item.front) }
    var back by remember { mutableStateOf(item.back.orEmpty()) }
    var kind by remember { mutableStateOf(item.kind) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text(stringResource(R.string.study_edit_title), color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyKindToggle(kind = kind, onChange = { kind = it })
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text(stringResource(R.string.study_front_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text(stringResource(R.string.study_back_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        item.copy(
                            kind = kind,
                            front = front.trim(),
                            back = back.trim().ifBlank { null },
                            editedByUser = true,
                        )
                    )
                },
                enabled = front.isNotBlank(),
            ) {
                Text(stringResource(R.string.speichern), color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.abbrechen), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun StudyItemAddDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String?) -> Unit,
) {
    var kind by remember { mutableStateOf("vocab") }
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text(stringResource(R.string.study_add_manual), color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyKindToggle(kind = kind, onChange = { kind = it })
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text(stringResource(R.string.study_front_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text(stringResource(R.string.study_back_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(kind, front.trim(), back.trim().ifBlank { null }) },
                enabled = front.isNotBlank(),
            ) {
                Text(stringResource(R.string.hinzufugen), color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.abbrechen), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun StudyKindToggle(kind: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("vocab" to R.string.study_vocab, "text_section" to R.string.study_text_section)
            .forEach { (value, label) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (kind == value) BgSurface else BgCard)
                        .clickable { onChange(value) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(label),
                        color = if (kind == value) TextPrimary else TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = if (kind == value) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
    }
}
