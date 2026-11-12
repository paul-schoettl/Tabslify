package com.tabslify.tabs.school.study

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabslify.R
import com.tabslify.core.objects.prvt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class StudyUploadImage(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
)

data class StudyUploadState(
    val enabled: Boolean = prvt(),
    val title: String = "",
    val subject: String = "",
    val minutesPerDay: Int = 25,
    val language: String = "la",
    val images: List<StudyUploadImage> = emptyList(),
    val busy: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val projectId: String? = null,
    val items: List<StudyItemDto> = emptyList(),
    val done: Boolean = false,
)

class StudyUploadViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StudyUploadState())
    val state: StateFlow<StudyUploadState> = _state.asStateFlow()

    fun setTitle(value: String) {
        if (!prvt()) return
        _state.value = _state.value.copy(title = value, error = null)
    }

    fun setSubject(value: String) {
        if (!prvt()) return
        _state.value = _state.value.copy(subject = value, error = null)
    }

    fun setMinutes(value: String) {
        if (!prvt()) return
        val parsed = value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(5, 120) ?: 25
        _state.value = _state.value.copy(minutesPerDay = parsed)
    }

    fun setLanguage(value: String) {
        if (!prvt()) return
        _state.value = _state.value.copy(language = value)
    }

    fun addImages(uris: List<Uri>) {
        if (!prvt()) return
        val current = _state.value.images
        val remaining = (MAX_IMAGES - current.size).coerceAtLeast(0)
        val added = uris.distinctBy { it.toString() }.take(remaining).map { StudyUploadImage(uri = it) }
        val tooMany = uris.size > remaining
        _state.value = _state.value.copy(
            images = current + added,
            error = if (tooMany) {
                getApplication<Application>().resources.getQuantityString(
                    R.plurals.study_max_images,
                    MAX_IMAGES,
                    MAX_IMAGES
                )
            } else {
                null
            },
        )
    }

    fun removeImage(id: String) {
        if (!prvt()) return
        _state.value = _state.value.copy(images = _state.value.images.filterNot { it.id == id })
    }

    fun moveImage(id: String, direction: Int) {
        if (!prvt()) return
        val images = _state.value.images.toMutableList()
        val index = images.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = index + direction
        if (target !in images.indices) return
        val item = images.removeAt(index)
        images.add(target, item)
        _state.value = _state.value.copy(images = images)
    }

    fun reset() {
        if (!prvt()) return
        _state.value = StudyUploadState()
    }

    fun uploadAndExtract() {
        if (!prvt()) return
        val app = getApplication<Application>()
        val current = _state.value
        if (current.busy) return
        if (current.images.isEmpty()) {
            _state.value = current.copy(error = app.getString(R.string.study_add_images_first))
            return
        }
        if (StudyRepository.currentUserId().isNullOrBlank()) {
            _state.value = current.copy(error = app.getString(R.string.study_login_required))
            return
        }
        startUpload(current, app)
    }

    private fun startUpload(current: StudyUploadState, app: Application) {
        _state.value = current.copy(busy = true, error = null, notice = null, progress = null)
        viewModelScope.launch {
            try {
                val title = current.title.trim().ifBlank {
                    current.subject.trim().ifBlank { app.getString(R.string.study_untitled) }
                }
                val project = StudyRepository.createProject(
                    title = title,
                    subject = current.subject.trim(),
                    minutesPerDay = current.minutesPerDay,
                    sourceLanguage = current.language.trim(),
                )
                val sourceIds = mutableListOf<String>()
                current.images.forEachIndexed { index, image ->
                    _state.value = _state.value.copy(
                        projectId = project.id,
                        progress = app.getString(R.string.study_progress_image, index + 1, current.images.size),
                    )
                    val source = StudyRepository.uploadSource(
                        context = app,
                        projectId = project.id,
                        uri = image.uri,
                        pageIndex = index + 1,
                    )
                    sourceIds += source.id
                }
                StudyRepository.updateProjectStatus(project.id, "extracting")
                _state.value = _state.value.copy(progress = app.getString(R.string.study_analyzing))
                val response = StudyRepository.extract(
                    projectId = project.id,
                    sourceIds = sourceIds,
                    languageHint = current.language.trim(),
                )
                val items = StudyRepository.loadItems(project.id)
                val notice = if (response.partial) app.getString(R.string.study_partial_warning) else null
                _state.value = _state.value.copy(
                    busy = false,
                    progress = null,
                    projectId = project.id,
                    items = items,
                    done = items.isNotEmpty(),
                    notice = notice,
                    error = if (items.isEmpty()) app.getString(R.string.study_no_items) else null,
                )
                runCatching {
                    StudyRepository.updateProjectStatus(project.id, if (items.isEmpty()) "draft" else "ready")
                }
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    progress = null,
                    error = error.localizedMessage ?: app.getString(R.string.study_extract_error),
                )
            }
        }
    }

    companion object {
        const val MAX_IMAGES = 6
    }
}
