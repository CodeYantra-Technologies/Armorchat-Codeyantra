package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.core.security.IdentityStorageException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class CreateIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(
        displayName: String = "",
        handle: String = "",
    ): Result<UserIdentity> {
        return try {
            val identity = identityRepository.createIdentity()
            if (displayName.isNotBlank() || handle.isNotBlank()) {
                identityRepository.updateProfile(displayName, handle)
            }
            Result.success(identityRepository.getIdentity() ?: identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IdentityStorageException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }
}
