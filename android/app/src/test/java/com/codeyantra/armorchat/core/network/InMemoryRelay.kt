package com.codeyantra.armorchat.core.network

import com.codeyantra.armorchat.domain.identity.Base64Url
import java.security.MessageDigest
import java.security.SecureRandom

class InMemoryRelayNetwork(
    val origin: String,
) {
    var suspended: Boolean = false
    var policyJson: String? = null
    private val random = SecureRandom()
    private val mailboxes = linkedMapOf<String, MailboxRegistration>()
    private val tokens = linkedMapOf<String, TokenRec>()
    private val queues = linkedMapOf<String, MutableList<Queued>>()
    private val invites = linkedMapOf<String, InviteRec>()
    private val mailboxInvites = linkedMapOf<String, String>()
    private val keyPackages = linkedMapOf<String, MutableList<KpRec>>()
    private val claims = linkedMapOf<String, String>()
    private val grants = linkedMapOf<String, GrantRec>()
    private val push = linkedMapOf<String, MutableMap<String, String>>()

    private data class TokenRec(
        val mailboxId: String,
        val kind: String,
        val grantId: String? = null,
        var expiresAt: Long? = null,
        var revoked: Boolean = false,
    )

    private data class InviteRec(
        val token: String,
        val mailboxId: String,
        val bundle: String,
        val expiresAt: Long,
        var revoked: Boolean = false,
    )

    private data class KpRec(
        val keyPackageId: String,
        val binding: String,
        val expiresAt: Long,
        var claimed: Boolean = false,
        var claimId: String? = null,
    )

    private data class GrantRec(
        val grantId: String,
        val mailboxId: String,
        val token: String,
        val kind: String,
        val expiresAt: Long,
        val inviteToken: String? = null,
        val claimId: String? = null,
        var revoked: Boolean = false,
    )

    private data class Queued(
        val envelope: ImmutableRelayEnvelope,
        val sha: String,
        var lease: String? = null,
        var consumed: Boolean = false,
    )

    fun client(): RelayClient = object : RelayClient {
        override val originHttps: String = origin

        override suspend fun registerMailbox(): MailboxRegistration {
            if (suspended) error("SERVICE_SUSPENDED")
            val box = MailboxRegistration(id(32), id(32), id(32))
            mailboxes[box.mailboxId] = box
            tokens[box.ownerToken] = TokenRec(box.mailboxId, "owner")
            tokens[box.submitCapability] = TokenRec(box.mailboxId, "submit")
            queues[box.mailboxId] = mutableListOf()
            keyPackages[box.mailboxId] = mutableListOf()
            return box
        }

        override suspend fun putKeyPackages(ownerToken: String, packagesJson: String) {
            if (suspended) error("SERVICE_SUSPENDED")
            val who = tokens[ownerToken] ?: error("UNAUTHORIZED")
            require(who.kind == "owner")
            val root = com.codeyantra.armorchat.core.json.CanonicalJson.parseObject(packagesJson)
            @Suppress("UNCHECKED_CAST")
            val list = root["key_packages"] as List<Map<String, Any?>>
            val stored = keyPackages.getOrPut(who.mailboxId) { mutableListOf() }
            for (item in list) {
                val kpId = item["key_package_id"] as String
                val existing = stored.find { it.keyPackageId == kpId }
                if (existing == null) {
                    stored += KpRec(
                        keyPackageId = kpId,
                        binding = item["binding"] as String,
                        expiresAt = item["expires_at"] as Long,
                    )
                }
            }
        }

        override suspend fun submitEnvelope(
            token: String,
            envelope: ImmutableRelayEnvelope,
        ): RelaySubmitResult {
            if (suspended) return RelaySubmitResult.ServiceSuspended(60)
            val rec = tokens[token] ?: return RelaySubmitResult.Rejected("UNAUTHORIZED")
            if (rec.revoked) return RelaySubmitResult.Rejected("UNAUTHORIZED")
            if (rec.kind == "owner") return RelaySubmitResult.Rejected("UNAUTHORIZED")
            if ((rec.kind == "bootstrap" || rec.kind == "peer") && rec.expiresAt != null && rec.expiresAt!! <= System.currentTimeMillis() / 1000) {
                return RelaySubmitResult.Rejected("UNAUTHORIZED")
            }
            val mailbox = rec.mailboxId
            if (mailbox != envelope.recipientMailbox) return RelaySubmitResult.Rejected("UNAUTHORIZED")
            val sha = sha256(envelope.canonicalJson())
            val q = queues.getOrPut(mailbox) { mutableListOf() }
            val existing = q.find { it.envelope.messageId == envelope.messageId }
            if (existing != null) {
                return RelaySubmitResult.Accepted(
                    messageId = envelope.messageId,
                    recipientMailbox = mailbox,
                    envelopeSha256 = existing.sha,
                    acceptedAt = 1,
                    expiresAt = envelope.expiresAt,
                    state = RelayQueueState.ACCEPTED,
                    replay = true,
                )
            }
            q += Queued(envelope, sha)
            return RelaySubmitResult.Accepted(
                messageId = envelope.messageId,
                recipientMailbox = mailbox,
                envelopeSha256 = sha,
                acceptedAt = 1,
                expiresAt = envelope.expiresAt,
                state = RelayQueueState.ACCEPTED,
                replay = false,
            )
        }

        override suspend fun fetchInbox(
            token: String,
            cursor: String?,
            limit: Int,
        ): RelayFetchResult {
            if (suspended) return RelayFetchResult.ServiceSuspended(60)
            val rec = tokens[token] ?: return RelayFetchResult.Rejected("UNAUTHORIZED")
            if (rec.kind != "owner") return RelayFetchResult.Rejected("UNAUTHORIZED")
            val mailbox = rec.mailboxId
            val items = queues[mailbox].orEmpty().filter { !it.consumed }.take(limit).map { row ->
                val lease = id(16)
                row.lease = lease
                LeasedRelayEnvelope(
                    messageId = row.envelope.messageId,
                    envelopeSha256 = row.sha,
                    encryptedPayloadB64 = row.envelope.encryptedPayloadB64,
                    payloadType = row.envelope.payloadType,
                    expiresAt = row.envelope.expiresAt,
                    leaseToken = lease,
                    leaseUntil = 60,
                    senderCredentialCanonical = row.envelope.senderCredentialCanonical,
                )
            }
            return RelayFetchResult.Leased(items, "")
        }

        override suspend fun consumeInbox(
            token: String,
            items: List<RelayConsumeItem>,
        ): RelayConsumeResult {
            val rec = tokens[token] ?: return RelayConsumeResult.Rejected("UNAUTHORIZED")
            if (rec.kind != "owner") return RelayConsumeResult.Rejected("UNAUTHORIZED")
            val mailbox = rec.mailboxId
            val q = queues[mailbox].orEmpty()
            for (item in items) {
                q.find { it.envelope.messageId == item.messageId }?.consumed = true
            }
            return RelayConsumeResult.Done(items.map { RelayQueueState.CONSUMED })
        }

        override suspend fun fetchNetworkPolicy(): String? = policyJson

        override suspend fun putInvite(
            ownerToken: String,
            token: String,
            bundleJson: String,
            expiresAt: Long,
        ): Boolean {
            if (suspended) error("SERVICE_SUSPENDED")
            val who = tokens[ownerToken] ?: return false
            if (who.kind != "owner") return false
            val mailbox = who.mailboxId
            mailboxInvites[mailbox]?.let { previous ->
                invites[previous]?.revoked = true
                grants.values.filter { it.inviteToken == previous && it.kind == GRANT_KIND_BOOTSTRAP }.forEach {
                    it.revoked = true
                    tokens[it.token]?.revoked = true
                }
            }
            invites[token] = InviteRec(token, mailbox, bundleJson, expiresAt)
            mailboxInvites[mailbox] = token
            return true
        }

        override suspend fun lookupInvite(token: String): String? {
            if (suspended) error("SERVICE_SUSPENDED")
            val rec = invites[token] ?: return null
            if (rec.revoked) return null
            if (rec.expiresAt <= System.currentTimeMillis() / 1000) return null
            return rec.bundle
        }

        override suspend fun revokeInvite(ownerToken: String, token: String): Boolean {
            val who = tokens[ownerToken] ?: return false
            if (who.kind != "owner") return false
            val rec = invites[token] ?: return true
            if (rec.mailboxId != who.mailboxId) return false
            rec.revoked = true
            grants.values.filter { it.inviteToken == token && it.kind == GRANT_KIND_BOOTSTRAP }.forEach {
                it.revoked = true
                tokens[it.token]?.revoked = true
            }
            return true
        }

        override suspend fun redeemInvite(token: String, claimId: String): InviteRedeemResult? {
            if (suspended) error("SERVICE_SUSPENDED")
            val invite = invites[token] ?: return null
            if (invite.revoked) return null
            if (invite.expiresAt <= System.currentTimeMillis() / 1000) return null
            val existingClaim = claims["${invite.mailboxId}:$claimId"]
            val pool = keyPackages[invite.mailboxId].orEmpty()
            if (existingClaim != null) {
                val kp = pool.find { it.keyPackageId == existingClaim } ?: return null
                val grant = grants.values.find { it.claimId == claimId && it.inviteToken == token } ?: return null
                if (grant.revoked) return null
                return InviteRedeemResult(invite.bundle, kp.keyPackageId, kp.binding, toGrant(grant), replay = true)
            }
            val next = pool.find { !it.claimed } ?: return null
            next.claimed = true
            next.claimId = claimId
            claims["${invite.mailboxId}:$claimId"] = next.keyPackageId
            val grant = GrantRec(
                grantId = id(16),
                mailboxId = invite.mailboxId,
                token = id(32),
                kind = GRANT_KIND_BOOTSTRAP,
                expiresAt = invite.expiresAt,
                inviteToken = token,
                claimId = claimId,
            )
            grants[grant.grantId] = grant
            tokens[grant.token] = TokenRec(grant.mailboxId, grant.kind, grant.grantId, grant.expiresAt)
            return InviteRedeemResult(invite.bundle, next.keyPackageId, next.binding, toGrant(grant), replay = false)
        }

        override suspend fun createPeerGrant(ownerToken: String, expiresAt: Long): SubmitGrant? {
            val who = tokens[ownerToken] ?: return null
            if (who.kind != "owner") return null
            val grant = GrantRec(
                grantId = id(16),
                mailboxId = who.mailboxId,
                token = id(32),
                kind = GRANT_KIND_PEER,
                expiresAt = expiresAt,
            )
            grants[grant.grantId] = grant
            tokens[grant.token] = TokenRec(grant.mailboxId, grant.kind, grant.grantId, grant.expiresAt)
            return toGrant(grant)
        }

        override suspend fun rotatePeerGrant(ownerToken: String, grantId: String): SubmitGrant? {
            val who = tokens[ownerToken] ?: return null
            if (who.kind != "owner") return null
            val existing = grants[grantId] ?: return null
            if (existing.mailboxId != who.mailboxId || existing.kind != GRANT_KIND_PEER) return null
            existing.revoked = true
            tokens[existing.token]?.revoked = true
            val next = GrantRec(
                grantId = id(16),
                mailboxId = who.mailboxId,
                token = id(32),
                kind = GRANT_KIND_PEER,
                expiresAt = existing.expiresAt,
            )
            grants[next.grantId] = next
            tokens[next.token] = TokenRec(next.mailboxId, next.kind, next.grantId, next.expiresAt)
            return toGrant(next)
        }

        override suspend fun revokeGrant(ownerToken: String, grantId: String): Boolean {
            val who = tokens[ownerToken] ?: return false
            if (who.kind != "owner") return false
            val rec = grants[grantId] ?: return true
            if (rec.mailboxId != who.mailboxId) return false
            rec.revoked = true
            tokens[rec.token]?.revoked = true
            return true
        }

        override suspend fun rotateSubmitCapability(ownerToken: String): String? {
            val who = tokens[ownerToken] ?: return null
            if (who.kind != "owner") return null
            val box = mailboxes[who.mailboxId] ?: return null
            tokens[box.submitCapability]?.revoked = true
            val next = id(32)
            mailboxes[box.mailboxId] = box.copy(submitCapability = next)
            tokens[next] = TokenRec(box.mailboxId, "submit")
            return next
        }

        override suspend fun registerPush(ownerToken: String, deviceId: String, fcmToken: String): Boolean {
            val who = tokens[ownerToken] ?: return false
            if (who.kind != "owner") return false
            val byDevice = push.getOrPut(who.mailboxId) { linkedMapOf() }
            byDevice[deviceId] = fcmToken
            return true
        }

        override suspend fun unregisterPush(ownerToken: String, deviceId: String): Boolean {
            val who = tokens[ownerToken] ?: return false
            if (who.kind != "owner") return false
            push[who.mailboxId]?.remove(deviceId)
            return true
        }
    }

    private fun toGrant(rec: GrantRec) = SubmitGrant(
        grantId = rec.grantId,
        token = rec.token,
        kind = rec.kind,
        mailboxId = rec.mailboxId,
        expiresAt = rec.expiresAt,
    )

    private fun id(n: Int): String {
        val bytes = ByteArray(n)
        random.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return Base64Url.encode(digest)
    }
}
