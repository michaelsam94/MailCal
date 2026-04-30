package com.michael.mailcal.feature_auth.presentation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michael.mailcal.core.common.AppContainer
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.feature_auth.domain.AuthUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val currentUser: AuthUser? = null,
    val signInIntent: Intent? = null,
    val error: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val authRepository = AppContainer.from(application).authRepository

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun prepareSignIn() {
        when (val result = authRepository.createSignInIntent()) {
            is AppResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            is AppResult.Success -> _uiState.value = _uiState.value.copy(signInIntent = result.data, error = null)
        }
    }

    fun consumeSignInIntent() {
        _uiState.value = _uiState.value.copy(signInIntent = null)
    }

    fun completeSignIn(redirectIntent: Intent) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = authRepository.completeSignIn(redirectIntent)) {
                is AppResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentUser = result.data,
                        signInIntent = null,
                        error = null
                    )
                }
            }
        }
    }

    fun loadCurrentUser() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                currentUser = authRepository.getCurrentUser(),
                error = null
            )
        }
    }
}
