package com.codeyantra.armorchat.data.identity

/**
 * QUARANTINED — Phase 0 NON-PRODUCTION UUID factory.
 *
 * Retained only as a historical marker. Do not inject or call from production
 * paths. Secure Ed25519 identity is created via [IdentityLocalDataSource] and
 * [com.codeyantra.armorchat.core.security.SecureIdentityKeyStore].
 */
@Deprecated(
    message = "NON-PRODUCTION. Use IdentityLocalDataSource / SecureIdentityKeyStore.",
    level = DeprecationLevel.ERROR,
)
object DevIdentityFactory {
    init {
        error("DevIdentityFactory must not be used. Production path uses Tink Ed25519.")
    }
}
