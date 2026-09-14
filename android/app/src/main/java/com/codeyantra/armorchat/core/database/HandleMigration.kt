package com.codeyantra.armorchat.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val HANDLE_MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE identities ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE identities ADD COLUMN handle TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE contacts ADD COLUMN handle TEXT")
        db.execSQL("ALTER TABLE contacts ADD COLUMN previousHandle TEXT")
        db.execSQL("ALTER TABLE contacts ADD COLUMN handleChangedAtEpochMs INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_invites (
                id TEXT NOT NULL PRIMARY KEY,
                token TEXT NOT NULL,
                expiresAtEpochSeconds INTEGER NOT NULL,
                inviteId TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val SESSION_MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact_sessions ADD COLUMN mlsSessionId TEXT")
    }
}

val INVITE_MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_invites ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'")
    }
}
