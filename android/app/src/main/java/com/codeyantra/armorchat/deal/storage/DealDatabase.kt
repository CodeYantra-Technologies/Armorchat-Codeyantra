package com.codeyantra.armorchat.deal.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Separate from ArmorChatDatabase so Armor Deal does not migrate message or
 * Mesh tables. Version 1 has no destructive fallback. Room enables foreign
 * keys by default. The app does not open this database until integration is
 * approved.
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class DealDatabase : RoomDatabase() {
    abstract fun dealDao(): DealDao

    companion object {
        const val FILE_NAME = "armorchat-deal.db"

        fun open(context: Context): DealDatabase =
            Room.databaseBuilder(context, DealDatabase::class.java, FILE_NAME).build()

        fun inMemory(context: Context): DealDatabase =
            Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).build()
    }
}
