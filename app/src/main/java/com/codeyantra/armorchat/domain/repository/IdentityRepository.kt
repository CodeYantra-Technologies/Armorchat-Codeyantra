package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.UserIdentity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun observeIdentity(): Flow<UserIdentity?>
    suspend fun getIdentity(): UserIdentity?
    /** Idempotent: returns existing decryptable identity or creates one. */
    suspend fun createIdentity(): UserIdentity
    suspend fun exportIdentityBackup(password: CharArray): ByteArray
    suspend fun restoreIdentityBackup(backupBytes: ByteArray, password: CharArray): UserIdentity
}
