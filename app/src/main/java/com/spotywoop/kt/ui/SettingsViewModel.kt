package com.spotywoop.kt.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spotywoop.kt.data.CommunitySessionStore
import com.spotywoop.kt.data.CommunityVerifier
import com.spotywoop.kt.data.SessionState
import com.spotywoop.kt.playback.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val sessionState: SessionState = SessionState.MISSING,
    val sessionId: String = "",
    val expiresAt: String = "",
    val verifying: Boolean = false,
    val error: String? = null,
    val success: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val session = CommunitySessionStore.load(ctx)
        val sessionState = CommunitySessionStore.sessionState(ctx)
        _state.update {
            it.copy(
                sessionState = sessionState,
                sessionId = session?.sessionId?.take(8)?.plus("…") ?: "",
                expiresAt = session?.expiresAt ?: "",
                error = null,
                success = null,
            )
        }
    }

    /**
     * Lance la vérification communautaire (Option B — deep link).
     * La coroutine se suspend jusqu'à ce que MainActivity appelle CommunityVerifier.deliverGrant().
     */
    fun startVerification() {
        if (_state.value.verifying) return
        _state.update { it.copy(verifying = true, error = null, success = null) }
        viewModelScope.launch {
            runCatching {
                CommunityVerifier.verify(ctx)
            }.onSuccess { session ->
                // Vide le cache audio car la nouvelle session peut débloquer des titres
                StreamResolver.invalidateAll()
                _state.update {
                    it.copy(
                        verifying = false,
                        sessionState = SessionState.VALID,
                        sessionId = session.sessionId.take(8) + "…",
                        expiresAt = session.expiresAt,
                        success = "Session vérifiée ✓",
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        verifying = false,
                        error = e.message ?: "Échec inconnu",
                        success = null,
                    )
                }
            }
        }
    }

    fun cancelVerification() {
        CommunityVerifier.cancel()
        _state.update { it.copy(verifying = false) }
    }

    fun clearSession() {
        CommunityVerifier.cancel()
        CommunitySessionStore.clear(ctx)
        StreamResolver.invalidateAll()
        _state.update {
            it.copy(
                sessionState = SessionState.MISSING,
                sessionId = "",
                expiresAt = "",
                success = null,
                error = null,
            )
        }
    }
}
