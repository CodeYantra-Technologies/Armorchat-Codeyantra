package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.UserIdentity
import javax.inject.Inject

/**
 * Secure backup / recovery is postponed to a later phase.
 * This use case always fails — the Restore UI is informational only.
 */
class RestoreIdentityUseCase @Inject constructor() {
    suspend operator fun invoke(@Suppress("UNUSED_PARAMETER") recoveryMaterial: String): Result<UserIdentity> =
        Result.failure(
            UnsupportedOperationException(
                "Secure identity backup and recovery is not available in this version.",
            ),
        )
}
