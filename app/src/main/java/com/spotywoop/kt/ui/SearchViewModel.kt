package com.spotywoop.kt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotywoop.kt.data.SearchResults
import com.spotywoop.kt.data.SpotifyClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchTab(val label: String) { Tracks("Tracks"), Albums("Albums"), Artists("Artists"), Playlists("Playlists") }

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val results: SearchResults? = null,
    val tab: SearchTab = SearchTab.Tracks,
)

class SearchViewModel(private val spotify: SpotifyClient = SpotifyClient()) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun onTabSelected(tab: SearchTab) = _state.update { it.copy(tab = tab) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
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
