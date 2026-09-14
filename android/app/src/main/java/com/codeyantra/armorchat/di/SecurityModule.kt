package com.codeyantra.armorchat.di

import com.codeyantra.armorchat.core.crypto.CryptoEngine
import com.codeyantra.armorchat.core.crypto.UnimplementedCryptoEngine
import com.codeyantra.armorchat.core.security.SecureIdentityKeyStore
import com.codeyantra.armorchat.core.security.TinkEd25519IdentityKeyStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    @Singleton
    abstract fun bindSecureIdentityKeyStore(
        impl: TinkEd25519IdentityKeyStore,
    ): SecureIdentityKeyStore

    @Binds
    @Singleton
    abstract fun bindCryptoEngine(impl: UnimplementedCryptoEngine): CryptoEngine
}
