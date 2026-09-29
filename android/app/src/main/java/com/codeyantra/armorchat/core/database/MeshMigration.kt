package com.codeyantra.armorchat.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MESH_MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mesh_custody` (" +
                "`id` TEXT NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, " +
                "`expireAtMs` INTEGER NOT NULL, " +
                "`hopCount` INTEGER NOT NULL, " +
                "`emergency` INTEGER NOT NULL, " +
                "`payload` BLOB NOT NULL, " +
                "`forwardedCount` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mesh_custody_expireAtMs` ON `mesh_custody` (`expireAtMs`)",
        )
    }
}
