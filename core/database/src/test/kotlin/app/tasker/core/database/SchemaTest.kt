package app.tasker.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations are written and tested against the exported schemas (tech plan §7.9), so the committed schema of the
 * current version must be the one the code expects: a database built from it opens with the current code (the
 * identity hashes match) and serves the DAOs' queries.
 */
@RunWith(AndroidJUnit4::class)
class SchemaTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TaskerDatabase::class.java)

    @Test
    fun `a database built from the exported schema opens with the current code`() = runTest {
        helper.createDatabase(NAME, TaskerDatabase.VERSION).use { db ->
            db.execSQL("INSERT INTO activity_day (day, first_seen_at) VALUES ($DAY, 0)")
        }

        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TaskerDatabase::class.java, NAME)
            .build()
        try {
            assertThat(database.metricsDao().firstActiveDay()).isEqualTo(DAY)
            assertThat(database.metricsDao().activeDays(DAY, DAY + 1)).isEqualTo(1)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "schema-test.db"
        const val DAY = 20_000L
    }
}
