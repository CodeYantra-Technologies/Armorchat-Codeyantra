package com.codeyantra.armorchat.fcm

import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ContactSessionDao
import com.codeyantra.armorchat.core.network.RelayProvider
import com.codeyantra.armorchat.core.security.RelayCredentialsKek
import com.codeyantra.armorchat.messaging.SafeLog
import com.codeyantra.armorchat.messaging.SessionOrchestrator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PushTokenRegistrar @Inject constructor(
    private val sessionDao: ContactSessionDao,
    private val relayKek: RelayCredentialsKek,
    private val provider: RelayProvider,
    dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val io = dispatchers.io
    @Volatile private var latestToken: String? = null
    @Volatile private var lastFingerprint: String? = null
    @Volatile private var lastMailboxId: String? = null

    fun onNewToken(token: String) {
        if (token.isBlank()) return
        scope.launch { registerLocked(token) }
    }

    fun onMailboxReady() {
        val token = latestToken ?: return
        scope.launch { registerLocked(token) }
    }

    private suspend fun registerLocked(token: String) = withContext(io) {
        mutex.withLock {
            latestToken = token
            val fp = FcmWake.fingerprint(token)
            val row = sessionDao.find(SessionOrchestrator.INVITE_PEER)
                ?: sessionDao.all().firstOrNull { !it.mailboxId.isNullOrBlank() && it.encryptedOwnerToken != null }
                ?: return@withLock
            val mailboxId = row.mailboxId ?: return@withLock
            val deviceId = row.deviceId?.takeIf { it.isNotBlank() } ?: return@withLock
            if (fp == lastFingerprint && mailboxId == lastMailboxId) return@withLock
            val owner = row.encryptedOwnerToken?.let { blob ->
                runCatching { String(relayKek.decrypt(blob), Charsets.UTF_8) }.getOrNull()
            } ?: return@withLock
            val ok = runCatching {
                provider.clientFor(row.relayOrigin).registerPush(owner, deviceId, token)
            }.getOrDefault(false)
            if (ok) {
                lastFingerprint = fp
                lastMailboxId = mailboxId
                SafeLog.event("push_register", "fp=$fp")
            } else {
                SafeLog.event("push_register", "fp=$fp result=retry")
            }
        }
    }
}
