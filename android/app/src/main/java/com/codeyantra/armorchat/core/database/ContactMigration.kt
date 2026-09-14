package com.codeyantra.armorchat.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Retains Phase 0 placeholder rows separately; they have no verifiable public keys. */
val CONTACT_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts RENAME TO legacy_contacts")
        db.execSQL("""CREATE TABLE IF NOT EXISTS contacts (
            id TEXT NOT NULL PRIMARY KEY,
            armorChatId TEXT NOT NULL,
            publicIdentityKey TEXT NOT NULL,
            displayName TEXT NOT NULL,
            createdAtEpochMs INTEGER NOT NULL,
            verificationMethod TEXT NOT NULL
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contacts_armorChatId ON contacts (armorChatId)")
    }
}
