package com.codeyantra.armorchat.di

import com.codeyantra.armorchat.core.network.MessageTransport
import com.codeyantra.armorchat.core.network.NoOpMessageTransport
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
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkProvideModule {
    /**
     * Reserved for a future transport. Not used in Phase 0.
     * INTERNET permission is intentionally omitted until a transport needs it.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
