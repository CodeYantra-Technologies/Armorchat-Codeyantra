package com.codeyantra.armorchat.di

import com.codeyantra.armorchat.data.contact.ContactRepositoryImpl
import com.codeyantra.armorchat.data.conversation.ConversationRepositoryImpl
import com.codeyantra.armorchat.data.conversation.MessageRepositoryImpl
import com.codeyantra.armorchat.data.identity.IdentityRepositoryImpl
import com.codeyantra.armorchat.data.settings.SettingsRepositoryImpl
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {
    @Binds
    @Singleton
    abstract fun bindIdentityRepository(impl: IdentityRepositoryImpl): IdentityRepository

    @Binds
    @Singleton
    abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
