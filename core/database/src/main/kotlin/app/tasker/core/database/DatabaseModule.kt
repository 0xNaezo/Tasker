package app.tasker.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TaskerDatabase =
        Room.databaseBuilder(context, TaskerDatabase::class.java, TaskerDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()
}

/** Ordered list of schema migrations; empty while the schema is at version 1. */
object Migrations {
    val ALL: Array<androidx.room.migration.Migration> = emptyArray()
}
