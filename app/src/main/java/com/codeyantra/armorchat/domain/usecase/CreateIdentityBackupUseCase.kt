package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.repository.IdentityRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class CreateIdentityBackupUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(password: CharArray): Result<ByteArray> {
        return try {
            Result.success(identityRepository.exportIdentityBackup(password))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
