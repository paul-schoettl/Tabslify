package com.tabslify.tabs.school.study

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.prvt
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

object StudyRepository {
    const val BUCKET = "study-media"
    private const val PREFS = "study_plan_cache"
    private const val SNAPSHOT_KEY = "snapshot"
    private const val MAX_IMAGE_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun ensurePrivate() {
        if (!prvt()) throw StudyPrivateOnlyException()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentUserId(): String? = Config.client.auth.currentUserOrNull()?.id

    suspend fun loadLatestProject(): StudyProjectDto? {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            Config.client.from("study_projects")
                .select {
                    order("last_opened_at", Order.DESCENDING)
                    limit(1)
                }
                .decodeList<StudyProjectDto>()
                .firstOrNull()
        }
    }

    suspend fun loadProject(projectId: String): StudyProjectDto? {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            Config.client.from("study_projects")
                .select {
                    filter { eq("id", projectId) }
                    limit(1)
                }
                .decodeList<StudyProjectDto>()
                .firstOrNull()
        }
    }

    suspend fun createProject(
        title: String,
        subject: String,
        minutesPerDay: Int,
        sourceLanguage: String,
    ): StudyProjectDto {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("title", title.trim())
                put("subject", subject.trim())
                put("plan_start", LocalDate.now().toString())
                put("plan_days", 5)
                put("minutes_per_day", minutesPerDay)
                put("source_language", sourceLanguage.trim().ifBlank { null })
                put("target_language", "de")
                put("status", "draft")
                put("algorithm_version", "srs-1")
                put("last_opened_at", LocalDateTime.now().toString())
            }
            Config.client.from("study_projects")
                .insert(payload) { select() }
                .decodeSingle<StudyProjectDto>()
        }
    }

    suspend fun updateProjectStatus(projectId: String, status: String) {
        ensurePrivate()
        withContext(Dispatchers.IO) {
            Config.client.from("study_projects").update(
                buildJsonObject {
                    put("status", status)
                    put("last_opened_at", LocalDateTime.now().toString())
                }
            ) {
                filter { eq("id", projectId) }
            }
        }
    }

    suspend fun uploadSource(
        context: Context,
        projectId: String,
        uri: Uri,
        pageIndex: Int,
    ): StudySourceDto {
        ensurePrivate()
        val userId = currentUserId() ?: throw IllegalStateException("not_authenticated")
        return withContext(Dispatchers.IO) {
            val bytes = compressImage(context, uri) ?: throw IllegalStateException("image_decode_failed")
            val sha = sha256(bytes)
            val storagePath = "$userId/$projectId/$pageIndex.jpg"
            Config.client.storage.from(BUCKET).upload(storagePath, bytes) { upsert = true }
            val payload = buildJsonObject {
                put("project_id", projectId)
                put("page_index", pageIndex)
                put("storage_path", storagePath)
                put("mime_type", "image/jpeg")
                put("sha256", sha)
                put("status", "uploaded")
            }
            Config.client.from("study_sources")
                .insert(payload) { select() }
                .decodeSingle<StudySourceDto>()
        }
    }

    suspend fun extract(
        projectId: String,
        sourceIds: List<String>,
        languageHint: String?,
    ): StudyExtractResponse {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("project_id", projectId)
                putJsonArray("source_ids") {
                    sourceIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
                put("language_hint", languageHint ?: "")
                put("prompt_version", "extract-1")
            }
            val response = Config.client.functions.invoke(
                function = "study-extract",
                body = body,
            )
            json.decodeFromString<StudyExtractResponse>(response.bodyAsText())
        }
    }

    suspend fun loadItems(projectId: String): List<StudyItemDto> {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            Config.client.from("study_items")
                .select {
                    filter { eq("project_id", projectId) }
                    order("source_page", Order.ASCENDING)
                }
                .decodeList<StudyItemDto>()
        }
    }

    suspend fun updateItem(item: StudyItemDto) {
        ensurePrivate()
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("kind", item.kind)
                put("front", item.front)
                put("back", item.back)
                put("context", item.context)
                put("reference_label", item.referenceLabel)
                put("difficulty", item.difficulty)
                put("confidence", item.confidence)
                put("edited_by_user", true)
            }
            Config.client.from("study_items").update(payload) {
                filter { eq("id", item.id) }
            }
        }
    }

    suspend fun deleteItem(itemId: String) {
        ensurePrivate()
        withContext(Dispatchers.IO) {
            Config.client.from("study_items").delete {
                filter { eq("id", itemId) }
            }
        }
    }

    suspend fun addManualItem(
        projectId: String,
        kind: String,
        front: String,
        back: String?,
    ): StudyItemDto {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            val source = Config.client.from("study_sources")
                .select {
                    filter { eq("project_id", projectId) }
                    order("page_index", Order.ASCENDING)
                    limit(1)
                }
                .decodeList<StudySourceDto>()
                .firstOrNull() ?: throw IllegalStateException("source_missing")

            val payload = buildJsonObject {
                put("project_id", projectId)
                put("source_id", source.id)
                put("kind", kind)
                put("front", front.trim())
                put("back", back?.trim())
                put("source_page", source.pageIndex)
                put("reference_label", "Manuell")
                put("difficulty", 3)
                put("confidence", 1.0)
                put("payload", buildJsonObject { })
                put("edited_by_user", true)
            }
            Config.client.from("study_items")
                .insert(payload) { select() }
                .decodeSingle<StudyItemDto>()
        }
    }

    suspend fun generatePlan(projectId: String, minutesPerDay: Int): StudyPlanResponse {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("project_id", projectId)
                put("start_date", LocalDate.now().toString())
                put("days", 5)
                put("minutes_per_day", minutesPerDay)
                putJsonArray("focus") {
                    add(kotlinx.serialization.json.JsonPrimitive("vocab"))
                    add(kotlinx.serialization.json.JsonPrimitive("text"))
                }
                put("regenerate", true)
            }
            val response = Config.client.functions.invoke(
                function = "study-plan",
                body = body,
            )
            json.decodeFromString<StudyPlanResponse>(response.bodyAsText())
        }
    }

    suspend fun loadSnapshot(projectId: String): StudyPlanResponse {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            val project = loadProject(projectId) ?: throw IllegalStateException("project_missing")
            val plan = Config.client.from("study_plans")
                .select {
                    filter {
                        eq("project_id", projectId)
                        eq("status", "active")
                    }
                    limit(1)
                }
                .decodeList<StudyPlanDto>()
                .firstOrNull()

            if (plan == null) {
                return@withContext StudyPlanResponse(
                    project = project,
                    items = loadItems(projectId),
                )
            }

            val days = Config.client.from("study_plan_days")
                .select {
                    filter { eq("plan_id", plan.id) }
                    order("day_index", Order.ASCENDING)
                }
                .decodeList<StudyPlanDayDto>()

            val assignments = Config.client.from("study_assignments")
                .select {
                    filter { eq("plan_id", plan.id) }
                    order("due_date", Order.ASCENDING)
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<StudyAssignmentDto>()

            val materials = Config.client.from("study_materials")
                .select {
                    filter { eq("project_id", projectId) }
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<StudyMaterialDto>()

            StudyPlanResponse(
                project = project,
                plan = plan,
                days = days,
                assignments = assignments,
                items = loadItems(projectId),
                materials = materials,
            )
        }
    }

    suspend fun loadLatestSnapshot(context: Context): StudyPlanResponse? {
        ensurePrivate()
        val project = loadLatestProject() ?: return null
        return loadSnapshot(project.id)
    }

    fun saveSnapshot(context: Context, response: StudyPlanResponse) {
        ensurePrivate()
        val snapshot = StudySnapshot(
            project = response.project,
            plan = response.plan,
            days = response.days,
            assignments = response.assignments,
            items = response.items,
            materials = response.materials,
            cachedAt = System.currentTimeMillis(),
        )
        prefs(context).edit().putString(SNAPSHOT_KEY, json.encodeToString(snapshot)).apply()
    }

    fun loadCachedSnapshot(context: Context): StudySnapshot? {
        ensurePrivate()
        val raw = prefs(context).getString(SNAPSHOT_KEY, null) ?: return null
        return runCatching { json.decodeFromString<StudySnapshot>(raw) }.getOrNull()
    }

    fun clearCache(context: Context) {
        ensurePrivate()
        prefs(context).edit().remove(SNAPSHOT_KEY).apply()
    }

    suspend fun markAssignmentDone(assignmentId: String): Boolean {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            runCatching {
                Config.client.from("study_assignments").update(
                    buildJsonObject {
                        put("status", "done")
                        put("completed_at", LocalDateTime.now().toString())
                    }
                ) {
                    filter { eq("id", assignmentId) }
                }
                true
            }.getOrDefault(false)
        }
    }

    suspend fun grade(
        assignmentId: String?,
        itemId: String,
        answer: String,
        mode: String,
        score: Float? = null,
        correct: Boolean? = null,
        latencyMs: Long? = null,
    ): StudyGradeResponse? {
        ensurePrivate()
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    if (assignmentId != null) put("assignment_id", assignmentId)
                    put("item_id", itemId)
                    put("answer", answer)
                    put("mode", mode)
                    if (score != null) put("score", score)
                    if (correct != null) put("correct", correct)
                    if (latencyMs != null) put("latency_ms", latencyMs)
                }
                val response = Config.client.functions.invoke(
                    function = "study-grade",
                    body = body,
                )
                json.decodeFromString<StudyGradeResponse>(response.bodyAsText())
            }.getOrNull()
        }
    }

    private fun compressImage(context: Context, uri: Uri): ByteArray? = try {
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.contentResolver, uri)
        ) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()
            output.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
