package io.github.nb1x.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecorderUiState(
    val isRecording: Boolean = false,
    val hasRecordings: Boolean = false,
    val recordingCount: Int = 0
)

object RecorderState {
    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    fun setRecording(recording: Boolean) {
        _state.value = _state.value.copy(isRecording = recording)
    }

    fun setCount(count: Int) {
        _state.value = _state.value.copy(
            hasRecordings = count > 0,
            recordingCount = count
        )
    }
}
