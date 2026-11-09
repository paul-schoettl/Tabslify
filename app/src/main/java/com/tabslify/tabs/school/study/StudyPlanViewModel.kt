package com.tabslify.tabs.school.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabslify.R
import com.tabslify.core.objects.prvt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StudyPlanUiState(
    val enabled: Boolean = prvt(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val project: StudyProjectDto? = null,
    val plan: StudyPlanDto? = null,
    val days: List<StudyPlanDayDto> = emptyList(),
    val assignments: List<StudyAssignmentDto> = emptyList(),
    val items: List<StudyItemDto> = emptyList(),
    val materials: List<StudyMaterialDto> = emptyList(),
    val offline: Boolean = false,
    val error: String? = null,
) {
    val pendingTodayCount: Int
        get() {
            val today = LocalDate.now().toString()
            return assignments.count { it.dueDate == today && it.status != "done" }
        }

    val doneCount: Int get() = assignments.count { it.status == "done" }
    val totalCount: Int get() = assignments.size

    fun item(id: String): StudyItemDto? = items.firstOrNull { it.id == id }

    fun material(id: String?): StudyMaterialDto? =
        if (id == null) null else materials.firstOrNull { it.id == id }

    fun todayDay(): StudyPlanDayDto? {
        val today = LocalDate.now().toString()
        return days.firstOrNull { it.date == today }
            ?: days.firstOrNull { it.status != "done" }
    }

    fun tasksFor(day: StudyPlanDayDto): List<StudySessionTask> =
        assignments.filter { it.dayId == day.id }
            .sortedBy { it.sortOrder }
            .mapNotNull { assignment ->
                item(assignment.itemId)?.let { StudySessionTask(assignment, it) }
            }
}

class StudyPlanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StudyPlanUiState())
    val state: StateFlow<StudyPlanUiState> = _state.asStateFlow()

    private var loadStarted = false

    fun loadIfNeeded() {
        if (!prvt()) {
            _state.value = _state.value.copy(enabled = false, loading = false)
            return
        }
        if (loadStarted) return
        loadStarted = true
        loadLatest()
    }

    fun loadLatest() {
        if (!prvt()) {
            _state.value = _state.value.copy(enabled = false, loading = false)
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val response = StudyRepository.loadLatestSnapshot(getApplication())
                if (response == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        project = null,
                        plan = null,
                        days = emptyList(),
                        assignments = emptyList(),
                        items = emptyList(),
                        materials = emptyList(),
                        offline = false,
                    )
                } else {
                    StudyRepository.saveSnapshot(getApplication(), response)
                    apply(response, offline = false)
                }
            } catch (error: Exception) {
                val cached = StudyRepository.loadCachedSnapshot(getApplication())
                if (cached != null) {
                    _state.value = StudyPlanUiState(
                        enabled = true,
                        loading = false,
                        project = cached.project,
                        plan = cached.plan,
                        days = cached.days,
                        assignments = cached.assignments,
                        items = cached.items,
                        materials = cached.materials,
                        offline = true,
                        error = null,
                    )
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.localizedMessage
                            ?: getApplication<Application>().getString(R.string.study_plan_error),
                    )
                }
            }
        }
    }

    fun openProject(projectId: String) {
        if (!prvt()) {
            _state.value = _state.value.copy(enabled = false)
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val response = StudyRepository.loadSnapshot(projectId)
                StudyRepository.saveSnapshot(getApplication(), response)
                apply(response, offline = false)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.localizedMessage
                        ?: getApplication<Application>().getString(R.string.study_plan_error),
                )
            }
        }
    }

    fun generatePlan(projectId: String, minutesPerDay: Int) {
        if (!prvt()) {
            _state.value = _state.value.copy(enabled = false)
            return
        }
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val response = StudyRepository.generatePlan(projectId, minutesPerDay)
                StudyRepository.saveSnapshot(getApplication(), response)
                apply(response, offline = false)
                _state.value = _state.value.copy(busy = false)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = error.localizedMessage
                        ?: getApplication<Application>().getString(R.string.study_plan_error),
                )
            }
        }
    }

    fun updateItem(item: StudyItemDto) {
        if (!prvt()) return
        viewModelScope.launch {
            runCatching { StudyRepository.updateItem(item) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { if (it.id == item.id) item else it },
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        error = it.localizedMessage
                            ?: getApplication<Application>().getString(R.string.study_save_failed),
                    )
                }
        }
    }

    fun deleteItem(itemId: String) {
        if (!prvt()) return
        viewModelScope.launch {
            runCatching { StudyRepository.deleteItem(itemId) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        items = _state.value.items.filterNot { it.id == itemId },
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        error = it.localizedMessage
                            ?: getApplication<Application>().getString(R.string.study_save_failed),
                    )
                }
        }
    }

    fun addManualItem(kind: String, front: String, back: String?) {
        if (!prvt()) return
        val projectId = _state.value.project?.id ?: return
        if (front.isBlank()) return
        viewModelScope.launch {
            runCatching { StudyRepository.addManualItem(projectId, kind, front, back) }
                .onSuccess { item ->
                    _state.value = _state.value.copy(items = _state.value.items + item)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        error = it.localizedMessage
                            ?: getApplication<Application>().getString(R.string.study_save_failed),
                    )
                }
        }
    }

    fun markDone(assignmentId: String) {
        if (!prvt()) return
        _state.value = _state.value.copy(
            assignments = _state.value.assignments.map {
                if (it.id == assignmentId) it.copy(status = "done") else it
            },
        )
        viewModelScope.launch {
            StudyRepository.markAssignmentDone(assignmentId)
        }
    }

    fun setSnapshot(response: StudyPlanResponse) {
        if (!prvt()) return
        StudyRepository.saveSnapshot(getApplication(), response)
        apply(response, offline = false)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun apply(response: StudyPlanResponse, offline: Boolean) {
        _state.value = StudyPlanUiState(
            enabled = true,
            loading = false,
            busy = false,
            project = response.project,
            plan = response.plan,
            days = response.days.sortedBy { it.dayIndex },
            assignments = response.assignments,
            items = response.items,
            materials = response.materials,
            offline = offline,
            error = null,
        )
    }
}
