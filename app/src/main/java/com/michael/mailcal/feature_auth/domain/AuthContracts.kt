package com.michael.mailcal.feature_auth.domain

import android.content.Intent
import com.michael.mailcal.core.common.AppResult

data class AuthUser(
    val id: String,
    val email: String,
    val displayName: String?
)

interface AuthRepository {
    fun createSignInIntent(): AppResult<Intent>
    suspend fun completeSignIn(redirectIntent: Intent): AppResult<AuthUser>
    suspend fun signOut(): AppResult<Unit>
    suspend fun getCurrentUser(): AuthUser?
}
