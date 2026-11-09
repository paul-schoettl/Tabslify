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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.tabslify.R
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun StudyMaterialScreen(
    state: StudyPlanUiState,
    onBack: () -> Unit,
    paddingValues: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StudyHeader(
            title = stringResource(R.string.study_material),
            subtitle = state.project?.title?.ifBlank { null },
            onBack = onBack,
            paddingValues = paddingValues,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.materials.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.study_material_empty),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            items(state.materials, key = { it.id }) { material ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BgCard)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StudyTypeChip(material.type)
                        StudySourceChip(
                            material.model?.takeIf { it.isNotBlank() } ?: "CommandCode"
                        )
                    }
                    Text(
                        text = material.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val markdown = material.markdownText()
                    if (markdown.isNotBlank()) {
                        Markdown(
                            content = markdown,
                            colors = markdownColor(text = TextPrimary),
                            typography = markdownTypography(),
                        )
                    } else {
                        Text(
                            text = material.payload.toString(),
                            color = TextTertiary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun StudyMaterialDto.markdownText(): String =
    payload["markdown"]?.jsonPrimitive?.contentOrNull
        ?: payload["text"]?.jsonPrimitive?.contentOrNull
        ?: payload["summary"]?.jsonPrimitive?.contentOrNull
        ?: ""
