package app.tasker.core.backup

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun backupFolder(folder: DocumentBackupFolder): BackupFolder

    companion object {
        /** `files/backups/`: the path the Auto Backup rules in `res/xml` refer to. */
        @Provides
        @Singleton
        fun backupStore(@ApplicationContext context: Context): BackupStore = BackupStore(File(context.filesDir, BackupStore.DIR_NAME))
    }
}
