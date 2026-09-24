package com.spotywoop.kt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotywoop.kt.data.SearchResults
import com.spotywoop.kt.data.SearchSuggestionsClient
import com.spotywoop.kt.data.SpotifyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchTab(val label: String) { Tracks("Tracks"), Albums("Albums"), Artists("Artists"), Playlists("Playlists") }

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val results: SearchResults? = null,
    val tab: SearchTab = SearchTab.Tracks,
    val suggestions: List<String> = emptyList(),
    val showSuggestions: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val spotify: SpotifyClient = SpotifyClient()) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _state
                .map { it.query }
                .distinctUntilChanged()
                .debounce(250)
                .collectLatest { q ->
                    val trimmed = q.trim()
                    if (trimmed.length >= 2 && _state.value.showSuggestions) {
                        val list = withContext(Dispatchers.IO) {
                            SearchSuggestionsClient.getSuggestions(trimmed)
                        }
                        if (_state.value.showSuggestions) {
                            _state.update { it.copy(suggestions = list) }
                        }
                    } else if (trimmed.length < 2) {
                        _state.update { it.copy(suggestions = emptyList()) }
                    }
                }
        }
    }

    fun onQueryChange(query: String) = _state.update {
        it.copy(
            query = query,
            showSuggestions = query.isNotBlank(),
            suggestions = if (query.isBlank()) emptyList() else it.suggestions,
        )
    }

    fun onTabSelected(tab: SearchTab) = _state.update { it.copy(tab = tab) }

    fun selectSuggestion(suggestion: String) {
        _state.update {
            it.copy(
                query = suggestion,
                suggestions = emptyList(),
                showSuggestions = false,
            )
        }
        search()
    }

    fun dismissSuggestions() {
        _state.update { it.copy(showSuggestions = false) }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(showSuggestions = false, suggestions = emptyList()) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val results = spotify.search(query)
                _state.update { it.copy(loading = false, results = results) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
