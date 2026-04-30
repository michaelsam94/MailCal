package com.michael.mailcal.feature_auth.data

import android.content.Context
import com.michael.mailcal.feature_auth.domain.AuthUser

class AuthSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(user: AuthUser, accessToken: String) {
        prefs.edit()
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun currentUser(): AuthUser? {
        val id = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
        return AuthUser(id = id, email = email, displayName = displayName)
    }

    private companion object {
        const val PREFS_NAME = "auth_session_prefs"
        const val KEY_USER_ID = "key_user_id"
        const val KEY_EMAIL = "key_email"
        const val KEY_DISPLAY_NAME = "key_display_name"
        const val KEY_ACCESS_TOKEN = "key_access_token"
    }
}
