package com.tabslify.tabs.school.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabslify.core.objects.prvt
import com.tabslify.tabs.school.vergleicheAntwort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class StudySessionUiState(
    val enabled: Boolean = prvt(),
    val tasks: List<StudySessionTask> = emptyList(),
    val index: Int = 0,
    val answers: List<StudyAnswer> = emptyList(),
    val pendingTextAnswer: String? = null,
    val lastAnswer: StudyAnswer? = null,
    val awaitingSelfGrade: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val current: StudySessionTask? get() = tasks.getOrNull(index)
    val total: Int get() = tasks.size
    val scorePercent: Int
        get() = if (answers.isEmpty()) 0
        else ((answers.sumOf { it.score.toDouble() } / answers.size) * 100).roundToInt()
    val weak: List<StudyAnswer> get() = answers.filter { !it.correct }
    val hasNext: Boolean get() = index + 1 < tasks.size
}

class StudySessionViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StudySessionUiState())
    val state: StateFlow<StudySessionUiState> = _state.asStateFlow()

    private var taskStartedAt = System.currentTimeMillis()

    fun start(tasks: List<StudySessionTask>) {
        if (!prvt()) {
            _state.value = StudySessionUiState(enabled = false)
            return
        }
        taskStartedAt = System.currentTimeMillis()
        _state.value = StudySessionUiState(
            enabled = true,
            tasks = tasks.sortedBy { it.assignment.sortOrder },
        )
    }

    fun submit(answer: String) {
        if (!prvt()) return
        val current = _state.value.current ?: return
        val item = current.item
        val trimmed = answer.trim()

        val expected = item.back
        if (item.kind == "vocab" && !expected.isNullOrBlank()) {
            val correct = matchesVocab(trimmed, expected)
            record(current, trimmed, if (correct) 1f else 0f, correct, null)
            return
        }

        _state.value = _state.value.copy(
            pendingTextAnswer = trimmed,
            awaitingSelfGrade = true,
            lastAnswer = null,
        )
    }

    fun dontKnow() {
        if (!prvt()) return
        val current = _state.value.current ?: return
        record(current, "", 0f, false, null)
    }

    fun submitSelfGrade(correct: Boolean) {
        if (!prvt()) return
        val current = _state.value.current ?: return
        val answer = _state.value.pendingTextAnswer.orEmpty()
        record(current, answer, if (correct) 1f else 0f, correct, null)
    }

    fun advance() {
        if (!prvt()) return
        val state = _state.value
        if (state.awaitingSelfGrade) return
        val next = state.index + 1
        if (next >= state.tasks.size) {
            _state.value = state.copy(finished = true, lastAnswer = null)
        } else {
            taskStartedAt = System.currentTimeMillis()
            _state.value = state.copy(
                index = next,
                pendingTextAnswer = null,
                lastAnswer = null,
                awaitingSelfGrade = false,
            )
        }
    }

    fun reset() {
        if (!prvt()) return
        _state.value = StudySessionUiState()
    }

    private fun record(
        task: StudySessionTask,
        answer: String,
        score: Float,
        correct: Boolean,
        feedback: String?,
    ) {
        val latency = System.currentTimeMillis() - taskStartedAt
        val entry = StudyAnswer(task, answer, score, correct, feedback)
        _state.value = _state.value.copy(
            answers = _state.value.answers + entry,
            pendingTextAnswer = null,
            awaitingSelfGrade = false,
            lastAnswer = entry,
        )
        viewModelScope.launch {
            StudyRepository.markAssignmentDone(task.assignment.id)
            StudyRepository.grade(
                assignmentId = task.assignment.id,
                itemId = task.item.id,
                answer = answer,
                mode = if (task.item.kind == "vocab") "typed" else "self",
                score = score,
                correct = correct,
                latencyMs = latency,
            )
        }
    }

    private fun matchesVocab(answer: String, expected: String): Boolean {
        if (answer.isBlank()) return false
        val variants = expected.split(",", ";", "/", " oder ", " bzw. ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(expected.trim()) }
        return variants.any { vergleicheAntwort(answer, it) }
    }
}
