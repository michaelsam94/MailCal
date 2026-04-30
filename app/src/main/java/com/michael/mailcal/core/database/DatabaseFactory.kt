package com.michael.mailcal.core.database

import android.content.Context
import androidx.room.Room

object DatabaseFactory {
    @Volatile
    private var db: MailCalDatabase? = null

    fun getInstance(context: Context): MailCalDatabase {
        return db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                MailCalDatabase::class.java,
                "mailcal.db"
            ).fallbackToDestructiveMigration().build().also { db = it }
        }
    }
}
