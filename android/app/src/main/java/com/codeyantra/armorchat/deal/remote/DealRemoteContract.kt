package com.codeyantra.armorchat.deal.remote

import com.codeyantra.armorchat.deal.domain.DealAccessException
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.domain.identity.Base64Url
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Server-derived object key. Clients may display it. They must not choose it,
 * and the worker ignores any client-supplied object key or party id.
 */
object DealObjectKeys {
    fun backup(dealId: String, versionId: String, partyId: String): String {
        DealIds.require(dealId)
        DealIds.require(versionId)
        DealIds.require(partyId)
        return "deals/$dealId/versions/$versionId/parties/$partyId/backup.enc"
    }
}

class BackupReceipt(
    val dealId: String,
    val versionId: String,
    val partyId: String,
    val objectKey: String,
    val ciphertextSha256: ByteArray,
    val confirmedAtEpochMs: Long,
) {
    override fun toString(): String = "BackupReceipt(dealId=$dealId, versionId=$versionId, partyId=$partyId)"
}

object BackupReceiptVerifier {
    fun verify(
        receipt: BackupReceipt,
        localCiphertext: ByteArray,
        objectExists: Boolean,
    ) {
        val expected = DealObjectKeys.backup(receipt.dealId, receipt.versionId, receipt.partyId)
        if (receipt.objectKey != expected) throw DealRecoveryException.Rejected()
        if (!objectExists) throw DealRecoveryException.MissingObject()
        if (receipt.ciphertextSha256.size != 32 ||
            !receipt.ciphertextSha256.contentEquals(sha256(localCiphertext))
        ) {
            throw DealRecoveryException.Corrupted()
        }
    }

    /** Both party receipts must exist and commit the same ciphertext hash. */
    fun assertPaired(receipts: List<BackupReceipt>, partyIds: Set<String>) {
        if (receipts.map { it.partyId }.toSet() != partyIds || receipts.size != partyIds.size) {
            throw DealRecoveryException.MissingObject()
        }
        val hashes = receipts.map { Base64Url.encode(it.ciphertextSha256) }.toSet()
        if (hashes.size != 1) throw DealCryptoException.InvalidSignature()
        val deals = receipts.map { it.dealId }.toSet()
        val versions = receipts.map { it.versionId }.toSet()
        if (deals.size != 1 || versions.size != 1) throw DealCryptoException.WrongDeal()
    }
}

/**
 * Execution requires a paired backup receipt for every party. The lifecycle
 * edge alone is not enough to mark a deal executed.
 */
object DealExecution {
    fun confirm(
        status: DealStatus,
        receipts: List<BackupReceipt>,
        partyIds: Set<String>,
        localCiphertext: ByteArray,
    ): DealStatus {
        BackupReceiptVerifier.assertPaired(receipts, partyIds)
        receipts.forEach { receipt ->
            BackupReceiptVerifier.verify(receipt, localCiphertext, objectExists = true)
        }
        val pending = if (status == DealStatus.FULLY_SIGNED) {
            DealLifecycle.transition(status, DealEvent.MARK_BACKUP_PENDING)
        } else {
            status
        }
        return DealLifecycle.transition(pending, DealEvent.EXECUTE)
    }
}

/**
 * Production authentication boundary. ArmorChat peer identity is not accepted
 * here. Phase 1 ships only this rejecting verifier in the app process.
 * The local-test token adapter lives in the worker test harness and in JVM
 * tests, and the worker refuses it when ENVIRONMENT is production.
 */
fun interface DealAuthenticator {
    fun authenticate(authorizationHeader: String?): String
}

class RejectingDealAuthenticator : DealAuthenticator {
    override fun authenticate(authorizationHeader: String?): String {
        throw DealAccessException.Unauthenticated()
    }
}

/**
 * HTTP client for `/v1/deals`. It never sends an object key or a party id on
 * backup calls. Not wired to the ArmorChat UI.
 */
class DealApiClient(
    private val baseUrl: String,
    private val authorizationHeader: () -> String,
) {
    fun prepareBackup(dealId: String, versionId: String, idempotencyKey: String): String {
        val response = exchange(
            method = "POST",
            path = "/v1/deals/$dealId/versions/$versionId/backups:prepare",
            json = "{}",
            idempotencyKey = idempotencyKey,
        )
        if (response.status !in 200..299) throw DealAccessException.Forbidden()
        return response.body
    }

    fun putBackup(dealId: String, versionId: String, uploadId: String, ciphertext: ByteArray, sha256Base64Url: String) {
        val response = exchange(
            method = "PUT",
            path = "/v1/deals/$dealId/versions/$versionId/backups/object",
            raw = ciphertext,
            extraHeaders = mapOf(
                "X-Upload-Id" to uploadId,
                "X-Ciphertext-Sha256" to sha256Base64Url,
                "Content-Type" to "application/octet-stream",
            ),
        )
        if (response.status !in 200..299) throw DealRecoveryException.Rejected()
    }

    private fun exchange(
        method: String,
        path: String,
        json: String? = null,
        raw: ByteArray? = null,
        idempotencyKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiBody {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", authorizationHeader())
        connection.setRequestProperty("Accept", "application/json")
        if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", idempotencyKey)
        extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val payload = raw ?: json?.toByteArray(StandardCharsets.UTF_8)
        if (payload != null) {
            connection.doOutput = true
            if (json != null) connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        return ApiBody(status, body)
    }

    private class ApiBody(val status: Int, val body: String)
}
