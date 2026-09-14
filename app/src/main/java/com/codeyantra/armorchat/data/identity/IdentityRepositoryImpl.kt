package com.codeyantra.armorchat.data.identity

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val local: IdentityLocalDataSource,
) : IdentityRepository {

    override fun observeIdentity(): Flow<UserIdentity?> = local.observeIdentity()

    override suspend fun getIdentity(): UserIdentity? = local.getIdentity()

    override suspend fun createIdentity(): UserIdentity = local.createIdentity()

    override suspend fun updateProfile(displayName: String, handle: String) =
        local.updateProfile(displayName, handle)

    override suspend fun exportIdentityBackup(password: CharArray): ByteArray =
        local.exportIdentityBackup(password)

    override suspend fun restoreIdentityBackup(
        backupBytes: ByteArray,
        password: CharArray,
    ): UserIdentity = local.restoreIdentityBackup(backupBytes, password)
}
