package com.codeyantra.armorchat.di

import com.codeyantra.armorchat.BuildConfig
import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.CodeYantraRelays
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.MessageTransport
import com.codeyantra.armorchat.core.network.NoOpMessageTransport
import com.codeyantra.armorchat.core.network.RelayProvider
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.ChatMessagingService
import com.codeyantra.armorchat.messaging.DefaultMessagingLinkFactory
import com.codeyantra.armorchat.messaging.MessagingLinkFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportBindModule {
    @Binds
    @Singleton
    abstract fun bindMessageTransport(impl: NoOpMessageTransport): MessageTransport

    @Binds
    @Singleton
    abstract fun bindChatMessaging(impl: ChatMessagingService): ChatMessagingController
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkProvideModule {
    /**
     * Debug two-phone and session setup use this client. Chat send is still
     * not enabled by [CodeYantraRelayTransport.isAvailable] (always false).
     * INTERNET is merged only in the debug manifest.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRelayRegistry(): RelayRegistry {
        val bootstrap = CodeYantraRelays.node1Bootstrap()
        return DefaultRelayRegistry(listOf(RelayNode(bootstrap.id, bootstrap.origin, RelayRole.BOOTSTRAP)))
    }

    @Provides
    @Singleton
    fun provideRelayProvider(registry: RelayRegistry, client: OkHttpClient): RelayProvider {
        return DefaultRelayProvider(registry) { origin ->
            CodeYantraRelayTransport(
                origin = CodeYantraRelayTransport.parseOrigin(origin),
                client = client,
            )
        }
    }

    @Provides
    @Singleton
    fun provideServicePolicyTrust(): ServicePolicyTrust = ServicePolicyTrust()

    @Provides
    @Singleton
    fun provideMessagingLinkFactory(): MessagingLinkFactory = DefaultMessagingLinkFactory
}
