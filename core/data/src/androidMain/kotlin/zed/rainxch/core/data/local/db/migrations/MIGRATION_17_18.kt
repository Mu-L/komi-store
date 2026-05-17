package zed.rainxch.core.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE installed_apps
                ADD COLUMN directUrlPollUrl TEXT DEFAULT NULL
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE installed_apps
                ADD COLUMN directUrlLastEtag TEXT DEFAULT NULL
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE installed_apps
                ADD COLUMN directUrlLastModified TEXT DEFAULT NULL
                """.trimIndent(),
            )
        }
    }
