package com.codeyantra.armorchat.deal.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Separate from ArmorChatDatabase so Armor Deal does not migrate message or
 * Mesh tables. There is no destructive fallback.
 *
 * Version 2 adds [DealDraftRow] only. [MIGRATION_1_2] creates that table and
 * does not rewrite deals, versions, signatures, receipts, audits, recovery, or
 * local keys. Version 3 adds [DealTransportRow] only. [MIGRATION_2_3] creates
 * that table and does not rewrite earlier rows. Version 4 adds [DealClosureRow]
 * only. [MIGRATION_3_4] creates that table and does not rewrite earlier rows.
 * Production navigation does not open this database. The debug lab and the
 * debug chat draft may open [FILE_NAME].
 */
@Database(
    entities = [
        DealRow::class,
        DealPartyRow::class,
        DealVersionRow::class,
        DealSignatureRow::class,
        DealBackupReceiptRow::class,
        DealAuditRow::class,
        DealRecoveryMetaRow::class,
        DealLocalKeyRow::class,
        DealDraftRow::class,
        DealTransportRow::class,
        DealClosureRow::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class DealDatabase : RoomDatabase() {
    abstract fun dealDao(): DealDao

    companion object {
        const val FILE_NAME = "armorchat-deal.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deal_drafts` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`dealId` TEXT NOT NULL, " +
                        "`versionId` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`contactId` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`sealedPayload` BLOB NOT NULL, " +
                        "`sealedPartyASigning` BLOB NOT NULL, " +
                        "`sealedPartyAEncryption` BLOB NOT NULL, " +
                        "`createdAtEpochMs` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`draftId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_deal_drafts_dealId` " +
                        "ON `deal_drafts` (`dealId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deal_drafts_conversationId` " +
                        "ON `deal_drafts` (`conversationId`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deal_transport` (" +
                        "`envelopeId` TEXT NOT NULL, " +
                        "`dealId` TEXT NOT NULL, " +
                        "`versionId` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`eventType` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`sealedEnvelope` BLOB NOT NULL, " +
                        "`createdAtEpochMs` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`envelopeId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deal_transport_dealId` " +
                        "ON `deal_transport` (`dealId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deal_transport_conversationId` " +
                        "ON `deal_transport` (`conversationId`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deal_closures` (" +
                        "`dealId` TEXT NOT NULL, " +
                        "`versionId` TEXT NOT NULL, " +
                        "`partyId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`signature` BLOB NOT NULL, " +
                        "`signerPublicKey` TEXT NOT NULL, " +
                        "`documentSha256` TEXT NOT NULL, " +
                        "`createdAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`dealId`, `versionId`, `partyId`, `kind`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deal_closures_dealId` " +
                        "ON `deal_closures` (`dealId`)",
                )
            }
        }

        @Volatile private var instance: DealDatabase? = null

        fun open(context: Context): DealDatabase {
            return instance ?: synchronized(this) {
                instance ?: builder(context.applicationContext, FILE_NAME)
                    .allowMainThreadQueries()
                    .build()
                    .also { instance = it }
            }
        }

        fun builder(context: Context, name: String): Builder<DealDatabase> =
            Room.databaseBuilder(context, DealDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun inMemory(context: Context): DealDatabase =
            Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).build()
    }
}
