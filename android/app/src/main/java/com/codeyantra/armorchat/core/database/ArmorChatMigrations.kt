package com.codeyantra.armorchat.core.database

import androidx.room.migration.Migration

/**
 * Canonical Room migration chain. DatabaseModule and tests must use this list
 * so Reply / Message Info schema bumps cannot drift out of the production builder.
 */
val ARMORCHAT_MIGRATIONS: Array<Migration> = arrayOf(
    CONTACT_MIGRATION_2_3,
    CONVERSATION_MIGRATION_3_4,
    MESSAGING_MIGRATION_4_5,
    MESSAGING_MIGRATION_5_6,
    HANDLE_MIGRATION_6_7,
    SESSION_MIGRATION_7_8,
    INVITE_MIGRATION_8_9,
    MESSAGE_RECEIPT_MIGRATION_9_10,
    MESSAGE_REPLY_MIGRATION_10_11,
    MESSAGE_TIMESTAMPS_MIGRATION_11_12,
    MESH_MIGRATION_12_13,
)
