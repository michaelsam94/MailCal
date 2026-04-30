package com.michael.mailcal.feature_auth.data

import android.net.Uri
import com.michael.mailcal.BuildConfig

object AuthConfig {
    val CLIENT_ID: String = BuildConfig.GMAIL_CLIENT_ID
    const val GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    const val GMAIL_TOKEN_SCOPE = "oauth2:$GMAIL_SCOPE"
}
