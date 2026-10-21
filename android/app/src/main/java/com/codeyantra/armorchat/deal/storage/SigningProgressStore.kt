package com.codeyantra.armorchat.deal.storage

import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.SigningProgress
import com.codeyantra.armorchat.deal.domain.SigningWorkflow

/**
 * Append-only signing journal in [DealAuditRow].
 * Name confirmations and SIGN evidence stay in that journal.
 * They are not inserted into deal signature rows.
 * When a deal row already exists, its status is updated to the mapped
 * [SigningProgress.storedStatus]. Recovery still ignores that status.
 */
class SigningProgressStore(
    private val dao: DealDao,
) {
    fun save(progress: SigningProgress, eventId: String, nowEpochMs: Long) {
        val outcome = SigningWorkflow.journalOutcome(progress)
        val already = dao.audits(progress.deal.dealId).any { row ->
            row.action == SigningWorkflow.JOURNAL_ACTION && row.outcome == outcome
        }
        if (!already) {
            dao.insertAudit(
                DealAuditRow(
                    eventId = eventId,
                    dealId = progress.deal.dealId,
                    action = SigningWorkflow.JOURNAL_ACTION,
                    outcome = outcome,
                    createdAtEpochMs = nowEpochMs,
                ),
            )
        }
        val existing = dao.findDeal(progress.deal.dealId) ?: return
        if (existing.status == progress.storedStatus.name &&
            existing.currentVersionId == progress.deal.versionId
        ) {
            return
        }
        dao.updateDeal(
            progress.deal.dealId,
            progress.storedStatus.name,
            progress.deal.versionId,
            nowEpochMs,
        )
    }

    fun recover(deal: CanonicalDeal): SigningProgress {
        val outcomes = dao.audits(deal.dealId)
            .filter { it.action == SigningWorkflow.JOURNAL_ACTION }
            .map { it.outcome }
        return SigningWorkflow.recover(deal, outcomes)
    }
}
