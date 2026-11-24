package com.cadfield.viewer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromSync(s: SyncState) = s.name
    @TypeConverter fun toSync(s: String) = SyncState.valueOf(s)
}

@Database(
    entities = [
        ProjectEntity::class, DocumentEntity::class, TagEntity::class, DocumentTagEntity::class,
        VersionEntity::class, SyncQueueEntity::class, AuditEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun documents(): DocumentDao
    abstract fun tags(): TagDao
    abstract fun versions(): VersionDao
    abstract fun syncQueue(): SyncQueueDao
    abstract fun audit(): AuditDao

    companion object {
        fun build(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "cadfield.db")
            // WAL: lecturas concurrentes y durabilidad ante cierres inesperados
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            // Nunca usar fallbackToDestructiveMigration: se perderían datos.
            .build()
    }
}
