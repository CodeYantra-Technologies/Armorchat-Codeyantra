package com.codeyantra.armorchat.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MESSAGING_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_sessions (
                contactId TEXT NOT NULL PRIMARY KEY,
                peerPin TEXT NOT NULL,
                relayOrigin TEXT NOT NULL,
                mailboxId TEXT,
                ownerToken TEXT,
                submitCapability TEXT,
                deviceId TEXT,
                binding TEXT,
                localRouteQr TEXT,
                peerRouteQr TEXT,
                mlsSnapshot BLOB,
                groupCreated INTEGER NOT NULL,
                state TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MESSAGING_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_sessions_new (
                contactId TEXT NOT NULL PRIMARY KEY,
                peerPin TEXT NOT NULL,
                relayOrigin TEXT NOT NULL,
                mailboxId TEXT,
                encryptedOwnerToken BLOB,
                encryptedSubmitCapability BLOB,
                deviceId TEXT,
                binding TEXT,
                localRouteQr TEXT,
                peerRouteQr TEXT,
                mlsSnapshot BLOB,
                groupCreated INTEGER NOT NULL,
                state TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO contact_sessions_new (
                contactId, peerPin, relayOrigin, mailboxId, encryptedOwnerToken, encryptedSubmitCapability,
                deviceId, binding, localRouteQr, peerRouteQr, mlsSnapshot, groupCreated, state, updatedAtEpochMs
            )
            SELECT
                contactId, peerPin, relayOrigin, mailboxId, NULL, NULL,
                deviceId, binding, localRouteQr, peerRouteQr, mlsSnapshot, groupCreated, state, updatedAtEpochMs
            FROM contact_sessions
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE contact_sessions")
        db.execSQL("ALTER TABLE contact_sessions_new RENAME TO contact_sessions")
    }
}

val MESSAGE_RECEIPT_MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN receiptSessionId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN receiptCiphertextSha256 TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN receiptEpoch INTEGER")
    }
}

val MESSAGE_REPLY_MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToJson TEXT")
    }
}

val MESSAGE_TIMESTAMPS_MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN deliveredAtEpochMs INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN readAtEpochMs INTEGER")
    }
}
