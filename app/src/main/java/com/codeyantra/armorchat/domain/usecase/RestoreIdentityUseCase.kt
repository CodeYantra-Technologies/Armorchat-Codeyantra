package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class RestoreIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(backupBytes: ByteArray, password: CharArray): Result<UserIdentity> {
        return try {
            Result.success(identityRepository.restoreIdentityBackup(backupBytes, password))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
