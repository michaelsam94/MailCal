package com.michael.mailcal.feature_auth.data

import android.content.Context
import android.content.Intent
import com.michael.mailcal.core.common.AppResult
import com.michael.mailcal.feature_auth.domain.AuthRepository
import com.michael.mailcal.feature_auth.domain.AuthUser
import com.michael.mailcal.feature_sync.domain.SyncRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val context: Context,
    private val syncRepository: SyncRepository
) : AuthRepository {
    private val signInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(AuthConfig.GMAIL_SCOPE))
            .build()
        GoogleSignIn.getClient(context, options)
    }
    private val sessionStore = AuthSessionStore(context)

    override fun createSignInIntent(): AppResult<Intent> {
        return AppResult.Success(signInClient.signInIntent)
    }

    override suspend fun completeSignIn(redirectIntent: Intent): AppResult<AuthUser> {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(redirectIntent)
                .getResult(ApiException::class.java)
                ?: return AppResult.Error("Google sign-in failed")
            val accountRef = account.account ?: return AppResult.Error("Google account not available")
            val accessToken = withContext(Dispatchers.IO) {
                GoogleAuthUtil.getToken(context, accountRef, AuthConfig.GMAIL_TOKEN_SCOPE)
            }
            val user = AuthUser(
                id = account.id ?: account.email.orEmpty(),
                email = account.email.orEmpty(),
                displayName = account.displayName
            )
            sessionStore.save(user, accessToken)
            when (val syncResult = syncRepository.setGmailAccessToken(accessToken)) {
                is AppResult.Error -> syncResult
                is AppResult.Success -> AppResult.Success(user)
            }
        } catch (e: ApiException) {
            AppResult.Error(
                "Google sign-in failed (code ${e.statusCode}): ${e.localizedMessage ?: "unknown"}",
                e
            )
        } catch (t: Throwable) {
            AppResult.Error("Sign-in failed: ${t.message ?: "unknown error"}", t)
        }
    }

    override suspend fun signOut(): AppResult<Unit> {
        sessionStore.clear()
        runCatching { signInClient.signOut() }
        return AppResult.Success(Unit)
    }

    override suspend fun getCurrentUser(): AuthUser? {
        val current = sessionStore.currentUser() ?: return null
        runCatching {
            val account = GoogleSignIn.getLastSignedInAccount(context)?.account
            if (account != null) {
                val freshToken = withContext(Dispatchers.IO) {
                    GoogleAuthUtil.getToken(context, account, AuthConfig.GMAIL_TOKEN_SCOPE)
                }
                syncRepository.setGmailAccessToken(freshToken)
                sessionStore.save(current, freshToken)
            }
        }
        return current
    }
}
