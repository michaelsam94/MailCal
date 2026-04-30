package com.michael.mailcal.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [EmailEntity::class, ParsedEventEntity::class, SyncStateEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MailCalDatabase : RoomDatabase() {
    abstract fun mailCalDao(): MailCalDao
}
