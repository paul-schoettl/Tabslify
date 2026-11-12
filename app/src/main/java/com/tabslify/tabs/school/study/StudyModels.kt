package com.tabslify.tabs.school.study

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class StudyProjectDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val title: String = "",
    val subject: String = "",
    @SerialName("exam_date") val examDate: String? = null,
    @SerialName("plan_start") val planStart: String = "",
    @SerialName("plan_days") val planDays: Int = 5,
    @SerialName("minutes_per_day") val minutesPerDay: Int = 25,
    @SerialName("source_language") val sourceLanguage: String? = null,
    @SerialName("target_language") val targetLanguage: String = "de",
    val status: String = "draft",
    @SerialName("algorithm_version") val algorithmVersion: String = "srs-1",
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
)

@Serializable
data class StudySourceDto(
    val id: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("page_index") val pageIndex: Int = 1,
    @SerialName("storage_path") val storagePath: String = "",
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    val sha256: String = "",
    val status: String = "uploaded",
    val error: String? = null,
)

@Serializable
data class StudyItemDto(
    val id: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("source_id") val sourceId: String = "",
    val kind: String = "vocab",
    val front: String = "",
    val back: String? = null,
    val context: String? = null,
    @SerialName("source_page") val sourcePage: Int = 1,
    @SerialName("source_line_from") val sourceLineFrom: Int? = null,
    @SerialName("source_line_to") val sourceLineTo: Int? = null,
    @SerialName("source_column") val sourceColumn: String? = null,
    @SerialName("source_excerpt") val sourceExcerpt: String? = null,
    @SerialName("reference_label") val referenceLabel: String = "",
    val language: String? = null,
    val difficulty: Int = 3,
    val confidence: Float = 0.5f,
    val tags: List<String> = emptyList(),
    val payload: JsonObject = buildJsonObject { },
    @SerialName("edited_by_user") val editedByUser: Boolean = false,
)

@Serializable
data class StudyMaterialDto(
    val id: String = "",
    @SerialName("project_id") val projectId: String = "",
    val type: String = "flashcard",
    val title: String = "",
    val payload: JsonObject = buildJsonObject { },
    @SerialName("prompt_version") val promptVersion: String = "",
    val model: String? = null,
)

@Serializable
data class StudyPlanDto(
    val id: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("minutes_per_day") val minutesPerDay: Int = 25,
    @SerialName("algorithm_version") val algorithmVersion: String = "srs-1",
    val status: String = "active",
    @SerialName("generation_meta") val generationMeta: JsonObject = buildJsonObject { },
)

@Serializable
data class StudyPlanDayDto(
    val id: String = "",
    @SerialName("plan_id") val planId: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("day_index") val dayIndex: Int = 1,
    val date: String = "",
    val focus: String = "",
    @SerialName("target_minutes") val targetMinutes: Int = 25,
    val status: String = "open",
)

@Serializable
data class StudyAssignmentDto(
    val id: String = "",
    @SerialName("plan_id") val planId: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("day_id") val dayId: String = "",
    @SerialName("item_id") val itemId: String = "",
    @SerialName("material_id") val materialId: String? = null,
    val kind: String = "learn",
    @SerialName("due_date") val dueDate: String = "",
    val priority: Int = 3,
    @SerialName("planned_minutes") val plannedMinutes: Int = 1,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val status: String = "pending",
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class StudyItemStatsDto(
    @SerialName("item_id") val itemId: String = "",
    val repetitions: Int = 0,
    @SerialName("interval_days") val intervalDays: Int = 0,
    val lapses: Int = 0,
    val streak: Int = 0,
    @SerialName("last_score") val lastScore: Float? = null,
    @SerialName("next_due_date") val nextDueDate: String? = null,
)

@Serializable
data class StudySourceResultDto(
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("page_index") val pageIndex: Int = 1,
    val status: String = "extracted",
    @SerialName("item_count") val itemCount: Int = 0,
    val error: String? = null,
)

@Serializable
data class StudyExtractResponse(
    @SerialName("job_id") val jobId: String = "",
    val status: String = "",
    val partial: Boolean = false,
    val sources: List<StudySourceResultDto> = emptyList(),
    @SerialName("item_counts") val itemCounts: Map<String, Int> = emptyMap(),
    @SerialName("total_items") val totalItems: Int = 0,
    val model: String = "",
    val provider: String = "",
    val reused: Boolean = false,
)

@Serializable
data class StudyPlanResponse(
    val project: StudyProjectDto = StudyProjectDto(),
    val plan: StudyPlanDto? = null,
    val days: List<StudyPlanDayDto> = emptyList(),
    val assignments: List<StudyAssignmentDto> = emptyList(),
    val items: List<StudyItemDto> = emptyList(),
    val materials: List<StudyMaterialDto> = emptyList(),
)

@Serializable
data class StudyGradeResponse(
    @SerialName("review_id") val reviewId: String? = null,
    val score: Float? = null,
    val correct: Boolean? = null,
    val feedback: String? = null,
    @SerialName("next_due_date") val nextDueDate: String? = null,
)

@Serializable
data class StudySnapshot(
    val project: StudyProjectDto = StudyProjectDto(),
    val plan: StudyPlanDto? = null,
    val days: List<StudyPlanDayDto> = emptyList(),
    val assignments: List<StudyAssignmentDto> = emptyList(),
    val items: List<StudyItemDto> = emptyList(),
    val materials: List<StudyMaterialDto> = emptyList(),
    val cachedAt: Long = 0L,
)

data class StudySessionTask(
    val assignment: StudyAssignmentDto,
    val item: StudyItemDto,
)

data class StudyAnswer(
    val task: StudySessionTask,
    val answer: String,
    val score: Float,
    val correct: Boolean,
    val feedback: String? = null,
)

class StudyPrivateOnlyException : IllegalStateException("study_private_only")

object StudyNavigation {
    var openRequested by mutableStateOf(false)
}
