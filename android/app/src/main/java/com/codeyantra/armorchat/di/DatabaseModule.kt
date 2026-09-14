package com.codeyantra.armorchat.di

import android.content.Context
import androidx.room.Room
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.core.database.dao.ConversationDao
import com.codeyantra.armorchat.core.database.dao.IdentityDao
import com.codeyantra.armorchat.core.database.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ArmorChatDatabase {
        // Phase 0 identity was NON-PRODUCTION (UUID). Destructive fallback from v1
        // is intentional — there were no real cryptographic identities to preserve.
        return Room.databaseBuilder(
            context,
            ArmorChatDatabase::class.java,
            "armorchat.db",
        )
            .addMigrations(*com.codeyantra.armorchat.core.database.ARMORCHAT_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .build()
    }

    @Provides
    fun identityDao(db: ArmorChatDatabase): IdentityDao = db.identityDao()

    @Provides
    fun contactDao(db: ArmorChatDatabase): ContactDao = db.contactDao()

    @Provides
    fun conversationDao(db: ArmorChatDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun messageDao(db: ArmorChatDatabase): MessageDao = db.messageDao()

    @Provides
    fun contactSessionDao(db: ArmorChatDatabase): com.codeyantra.armorchat.core.database.dao.ContactSessionDao =
        db.contactSessionDao()

    @Provides
    fun localInviteDao(db: ArmorChatDatabase): com.codeyantra.armorchat.core.database.dao.LocalInviteDao =
        db.localInviteDao()
}
