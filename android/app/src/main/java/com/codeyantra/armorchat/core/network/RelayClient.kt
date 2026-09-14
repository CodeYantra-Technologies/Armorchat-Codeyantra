package com.codeyantra.armorchat.core.network

/**
 * HTTPS ciphertext-queue client. Origins are selected by [RelayRegistry];
 * this interface is not coupled to any single hostname.
 */
interface RelayClient {
    /** Canonical https origin, no path/query/fragment. */
    val originHttps: String

    suspend fun registerMailbox(): MailboxRegistration

    suspend fun putKeyPackages(ownerToken: String, packagesJson: String)

    suspend fun submitEnvelope(
        token: String,
        envelope: ImmutableRelayEnvelope,
    ): RelaySubmitResult

    suspend fun fetchInbox(
        token: String,
        cursor: String? = null,
        limit: Int = 10,
    ): RelayFetchResult

    suspend fun consumeInbox(
        token: String,
        items: List<RelayConsumeItem>,
    ): RelayConsumeResult

    /** Signed network policy document, or null if the node does not serve one. */
    suspend fun fetchNetworkPolicy(): String?

    /** Owner-authenticated invite publish. Token stays in the JSON body, never the URL. */
    suspend fun putInvite(ownerToken: String, token: String, bundleJson: String, expiresAt: Long): Boolean

    /** Unauthenticated lookup. Returns signed bundle JSON or null if unknown/expired/revoked. */
    suspend fun lookupInvite(token: String): String?

    suspend fun revokeInvite(ownerToken: String, token: String): Boolean

    /**
     * Per-peer redeem: claims one KeyPackage and issues a bootstrap grant.
     * Idempotent on [claimId]. Returns null for unknown/expired/revoked invites.
     */
    suspend fun redeemInvite(token: String, claimId: String): InviteRedeemResult?

    suspend fun createPeerGrant(ownerToken: String, expiresAt: Long): SubmitGrant?

    suspend fun rotatePeerGrant(ownerToken: String, grantId: String): SubmitGrant?

    suspend fun revokeGrant(ownerToken: String, grantId: String): Boolean

    suspend fun rotateSubmitCapability(ownerToken: String): String?

    /** Owner-authenticated FCM token bind. Never put the token in a URL. */
    suspend fun registerPush(ownerToken: String, deviceId: String, fcmToken: String): Boolean = false

    suspend fun unregisterPush(ownerToken: String, deviceId: String): Boolean = false

    suspend fun uploadBlob(ownerToken: String, blobId: String, ciphertext: ByteArray): Boolean = false

    suspend fun downloadBlob(token: String, blobId: String): ByteArray? = null

    suspend fun deleteBlob(ownerToken: String, blobId: String): Boolean = false
}

data class InviteRedeemResult(
    val bundleJson: String,
    val keyPackageId: String,
    val binding: String,
    val grant: SubmitGrant,
    val replay: Boolean,
)
