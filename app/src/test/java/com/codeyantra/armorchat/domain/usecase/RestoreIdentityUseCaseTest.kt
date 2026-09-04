package com.codeyantra.armorchat.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RestoreIdentityUseCaseTest {
    @Test
    fun alwaysFails_secureRecoveryNotAvailable() = runTest {
        val useCase = RestoreIdentityUseCase()
        val result = useCase("any-material")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(UnsupportedOperationException::class.java)
    }
}
