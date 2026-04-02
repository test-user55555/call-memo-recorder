package com.example.callmemorecorder.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.domain.model.RecordItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val recordRepository: RecordRepository
) : ViewModel() {

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(container.recordRepository) as T
        }
    }

    val records: StateFlow<List<RecordItem>> = recordRepository.getAllRecords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _deleteResult = MutableStateFlow<String?>(null)
    val deleteResult: StateFlow<String?> = _deleteResult.asStateFlow()

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            try {
                recordRepository.deleteById(id)
                _deleteResult.value = "deleted"
            } catch (e: Exception) {
                _deleteResult.value = "error: ${e.message}"
            }
        }
    }

    fun clearDeleteResult() { _deleteResult.value = null }
}
