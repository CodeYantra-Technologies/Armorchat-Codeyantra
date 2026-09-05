package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.core.security.IdentityStorageException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class CreateIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(): Result<UserIdentity> {
        return try {
            Result.success(identityRepository.createIdentity())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IdentityStorageException) {
            Result.failure(e)
        }
    }
}
