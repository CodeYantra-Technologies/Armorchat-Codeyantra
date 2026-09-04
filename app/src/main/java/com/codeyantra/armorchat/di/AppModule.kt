package com.codeyantra.armorchat.di

import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.common.DefaultAppDispatchers
import com.codeyantra.armorchat.core.common.SystemAppClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemAppClock): AppClock
}

@Module
@InstallIn(SingletonComponent::class)
object AppProvideModule {
    @Provides
    @Singleton
    fun provideDispatchers(default: DefaultAppDispatchers): AppDispatchers = default.dispatchers
}
