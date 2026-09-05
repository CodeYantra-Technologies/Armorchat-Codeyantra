package com.codeyantra.armorchat.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 1C.1: bind conversations to contacts (unique contactId) and extend messages
 * for UI foundation. Phase 0 conversation/message stubs had no contact binding and
 * no production UI — recreate those tables while preserving identities and contacts.
 */
val CONVERSATION_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT NOT NULL PRIMARY KEY,
                contactId TEXT NOT NULL,
                title TEXT,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_contactId ON conversations (contactId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                type TEXT NOT NULL,
                isOutgoing INTEGER NOT NULL,
                localPlaintext TEXT,
                ciphertext BLOB,
                status TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)",
        )
    }
}
