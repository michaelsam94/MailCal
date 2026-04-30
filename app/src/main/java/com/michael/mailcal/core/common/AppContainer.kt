package com.michael.mailcal.core.common

import android.content.Context
import com.michael.mailcal.core.database.DatabaseFactory
import com.michael.mailcal.core.network.GmailApiClient
import com.michael.mailcal.feature_auth.data.AuthRepositoryImpl
import com.michael.mailcal.feature_auth.domain.AuthRepository
import com.michael.mailcal.feature_calendar.data.CalendarRepositoryImpl
import com.michael.mailcal.feature_calendar.domain.CalendarRepository
import com.michael.mailcal.feature_sync.data.EmailEventParser
import com.michael.mailcal.feature_sync.data.SyncRepositoryImpl
import com.michael.mailcal.feature_sync.domain.SyncRepository

class AppContainer private constructor(context: Context) {
    private val db = DatabaseFactory.getInstance(context)

    val syncRepository: SyncRepository by lazy {
        SyncRepositoryImpl(
            dao = db.mailCalDao(),
            gmailApiClient = GmailApiClient(),
            parser = EmailEventParser()
        )
    }
    val authRepository: AuthRepository by lazy { AuthRepositoryImpl(context, syncRepository) }
    val calendarRepository: CalendarRepository by lazy { CalendarRepositoryImpl(context) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun from(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}
