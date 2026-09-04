package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.repository.IdentityRepository
import javax.inject.Inject

class ObserveIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    operator fun invoke() = identityRepository.observeIdentity()
}
