package com.codeyantra.armorchat.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.core.database.dao.ConversationDao
import com.codeyantra.armorchat.core.database.dao.IdentityDao
import com.codeyantra.armorchat.core.database.dao.MessageDao
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.IdentityEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity

@Database(
    entities = [
        IdentityEntity::class,
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ArmorChatDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
