package com.codeyantra.armorchat.deal.lab

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codeyantra.armorchat.deal.crypto.AndroidKeystoreDealKeyProtector
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LabPage { HOME, CREATE, REVIEW, SIGN, BACKUP, HISTORY, STORED }

class DealLabViewModel(val controller: DealLabController) : ViewModel() {
    var page by mutableStateOf(LabPage.HOME)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var form by mutableStateOf(LabForm())
        private set
    var showErrors by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var noticeIsError by mutableStateOf(false)
        private set
    var secretHex by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    private var secret: ByteArray? = null

    fun showHome() {
        page = LabPage.HOME
        selectedId = null
        showErrors = false
        bump()
    }

    fun newDeal() {
        val id = try {
            controller.beginBlank()
        } catch (e: DealException) {
            notice = e.message
            noticeIsError = true
            bump()
            return
        }
        selectedId = id
        form = LabForm()
        showErrors = false
        page = LabPage.CREATE
        notice = "This is a synthetic demo. It is not a real agreement, and it stays on this device."
        noticeIsError = false
        bump()
    }

    fun open(summary: LabSummary) {
        if (!summary.inSession) {
            val result = controller.openStored(summary.dealId)
            notice = result.message
            noticeIsError = result is LabResult.Rejected
            page = if (result is LabResult.Ok) LabPage.STORED else LabPage.HOME
            selectedId = summary.dealId
            bump()
            return
        }
        selectedId = summary.dealId
        val snap = controller.snapshot(summary.dealId)
        form = snap.form
        showErrors = false
        page = if (snap.actions.canEdit) LabPage.CREATE else LabPage.REVIEW
        notice = null
        bump()
    }

    fun go(next: LabPage) {
        if (selectedId == null && next != LabPage.HOME) return
        page = next
        bump()
    }

    fun onForm(value: LabForm) {
        form = value
    }

    fun saveDraft() {
        commit(stayOnCreate = true)
    }

    fun continueToReview() {
        if (commit(stayOnCreate = false)) {
            page = LabPage.REVIEW
            notice = "Review this draft. Nobody has signed yet."
            noticeIsError = false
            bump()
        }
    }

    fun useForSignatures() = runOnSelected { controller.apply(it, DealEvent.PROPOSE) }
    fun cancelDeal() = runOnSelected { controller.apply(it, DealEvent.CANCEL) }
    fun signLocal() = runOnSelected { controller.signLocal(it) }
    fun signSimulated() = runOnSelected { controller.signSimulatedCounterparty(it) }
    fun refuseExecute() = runOnSelected { controller.apply(it, DealEvent.EXECUTE) }

    fun newSecret() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secret = bytes
        secretHex = bytes.joinToString("") { "%02x".format(it) }
        notice = "Synthetic 32-byte secret is on screen. It is not a cloud credential."
        noticeIsError = false
        bump()
    }

    fun seal() {
        val bytes = secret
        if (bytes == null) {
            notice = "Generate a synthetic secret before sealing. Local backup is not verified."
            noticeIsError = true
            bump()
            return
        }
        runOnSelected { controller.sealLocalPackage(it, bytes) }
    }

    fun verifyCurrent() {
        val bytes = secret
        if (bytes == null) {
            notice = "Local backup is not verified. There is no synthetic secret in this session."
            noticeIsError = true
            bump()
            return
        }
        runOnSelected { controller.verifyLocalPackage(it, bytes) }
    }

    fun verifyWrong() {
        val bytes = secret?.copyOf()
        if (bytes == null) {
            notice = "Local backup is not verified. There is no synthetic secret in this session."
            noticeIsError = true
            bump()
            return
        }
        bytes[0] = (bytes[0].toInt() xor 0xff).toByte()
        runOnSelected { controller.verifyLocalPackage(it, bytes) }
    }

    private fun commit(stayOnCreate: Boolean): Boolean {
        val id = selectedId ?: return false
        val trimmed = form.trimmed()
        form = trimmed
        showErrors = true
        val result = try {
            controller.updateForm(id, trimmed)
        } catch (e: DealException) {
            notice = e.message
            noticeIsError = true
            bump()
            return false
        }
        notice = result.message
        noticeIsError = result is LabResult.Rejected
        if (stayOnCreate || result is LabResult.Rejected) bump()
        return result is LabResult.Ok
    }

    private fun runOnSelected(block: (String) -> LabResult) {
        val id = selectedId ?: return
        run { block(id) }
    }

    private fun run(block: () -> LabResult) {
        notice = try {
            val result = block()
            noticeIsError = result is LabResult.Rejected
            result.message
        } catch (e: DealException) {
            noticeIsError = true
            e.message
        }
        bump()
    }

    private fun bump() {
        revision += 1
    }
}

private object DealLabDatabaseHolder {
    fun get(context: Context): DealDatabase = DealDatabase.open(context)
}

@Composable
fun DealLabRoute(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val model: DealLabViewModel = viewModel {
        DealLabViewModel(
            DealLabController(
                repository = DealRepository(DealLabDatabaseHolder.get(context)),
                protector = AndroidKeystoreDealKeyProtector(),
            ),
        )
    }
    DealLabScreen(model = model, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealLabScreen(model: DealLabViewModel, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tick = model.revision
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(DealLabDestination.TITLE) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (model.page == LabPage.HOME) onBack() else model.showHome()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Banner()
            when (model.page) {
                LabPage.HOME -> HomePage(model, tick)
                LabPage.CREATE -> SessionFrame(model, tick, LabPage.CREATE)
                LabPage.REVIEW -> SessionFrame(model, tick, LabPage.REVIEW)
                LabPage.SIGN -> SessionFrame(model, tick, LabPage.SIGN)
                LabPage.BACKUP -> SessionFrame(model, tick, LabPage.BACKUP)
                LabPage.HISTORY -> SessionFrame(model, tick, LabPage.HISTORY)
                LabPage.STORED -> StoredPage(model)
            }
            model.notice?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (model.noticeIsError) colors.error else colors.onSurface,
                )
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun Banner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = DealLabDestination.BANNER,
            modifier = Modifier
                .padding(12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun HomePage(model: DealLabViewModel, tick: Int) {
    val deals = androidx.compose.runtime.remember(tick) { model.controller.summaries() }
    Text(
        "Synthetic demo agreements on this device. These are not real contracts.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = model::newDeal, modifier = Modifier.labButton()) {
        Text("New Deal")
    }
    if (deals.isEmpty()) {
        Text(
            "No demo agreements yet. New Deal starts a blank synthetic agreement. It is not saved until both demo parties sign.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    deals.forEach { deal ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { model.open(deal) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(deal.title, style = MaterialTheme.typography.titleSmall)
                Text(deal.statusLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Synthetic demo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    when {
                        deal.backupVerified -> DealLabController.VERIFIED_BACKUP
                        deal.persisted -> "Saved as ciphertext. Local backup is not verified."
                        else -> "In this session only. Not saved yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionFrame(model: DealLabViewModel, tick: Int, page: LabPage) {
    val id = model.selectedId ?: return
    val snap = androidx.compose.runtime.remember(tick, id) { model.controller.snapshot(id) }
    StepPicker(page = page, onSelect = model::go)
    Text(
        "${snap.statusLabel} · ${snap.versionLabel}",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "Synthetic demo. Not a real agreement.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
    )
    when (page) {
        LabPage.CREATE -> CreateBody(model, snap)
        LabPage.REVIEW -> ReviewBody(model, snap)
        LabPage.SIGN -> SignBody(model, snap)
        LabPage.BACKUP -> BackupBody(model, snap)
        LabPage.HISTORY -> HistoryBody(model, id, tick)
        else -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepPicker(page: LabPage, onSelect: (LabPage) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            LabPage.CREATE to "Create",
            LabPage.REVIEW to "Review",
            LabPage.SIGN to "Sign",
            LabPage.BACKUP to "Backup",
            LabPage.HISTORY to "History",
        ).forEach { (target, label) ->
            val modifier = Modifier.heightIn(min = 48.dp)
            if (page == target) {
                Button(onClick = { onSelect(target) }, modifier = modifier) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(target) }, modifier = modifier) { Text(label) }
            }
        }
    }
}

@Composable
private fun CreateBody(model: DealLabViewModel, snap: LabDealSnapshot) {
    val errors = if (model.showErrors) LabAgreement.fieldErrors(model.form) else emptyMap()
    Text(
        "Party A uses a synthetic key on this device. Party B is simulated on this same device. Neither signature is a legal eSign.",
        style = MaterialTheme.typography.bodyMedium,
    )
    LabField(
        value = model.form.title,
        onValueChange = { model.onForm(model.form.copy(title = it)) },
        label = "Agreement title",
        enabled = snap.actions.canEdit,
        error = errors[LabField.TITLE],
    )
    LabField(
        value = model.form.partyAName,
        onValueChange = { model.onForm(model.form.copy(partyAName = it)) },
        label = "Party A name",
        enabled = snap.actions.canEdit,
        error = errors[LabField.PARTY_A_NAME],
    )
    LabField(
        value = model.form.partyARole,
        onValueChange = { model.onForm(model.form.copy(partyARole = it)) },
        label = "Party A role",
        enabled = snap.actions.canEdit,
        error = errors[LabField.PARTY_A_ROLE],
        supporting = "What Party A is doing in the agreement. For example, Client.",
    )
    LabField(
        value = model.form.partyBName,
        onValueChange = { model.onForm(model.form.copy(partyBName = it)) },
        label = "Party B name",
        enabled = snap.actions.canEdit,
        error = errors[LabField.PARTY_B_NAME],
        supporting = "Party B is simulated. No remote person is contacted.",
    )
    LabField(
        value = model.form.partyBRole,
        onValueChange = { model.onForm(model.form.copy(partyBRole = it)) },
        label = "Party B role",
        enabled = snap.actions.canEdit,
        error = errors[LabField.PARTY_B_ROLE],
        supporting = "What the simulated party is doing. For example, Studio.",
    )
    LabField(
        value = model.form.amountText,
        onValueChange = { model.onForm(model.form.copy(amountText = it)) },
        label = "Amount",
        enabled = snap.actions.canEdit,
        error = errors[LabField.AMOUNT],
        supporting = "Rupees, such as 1500 or 1500.50. The signed record stores paise.",
        keyboardType = KeyboardType.Decimal,
    )
    OutlinedTextField(
        value = "INR",
        onValueChange = {},
        readOnly = true,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Currency") },
        supportingText = { Text("Indian rupees for this demo.") },
    )
    LabField(
        value = model.form.deadline,
        onValueChange = { model.onForm(model.form.copy(deadline = it)) },
        label = "Delivery or completion deadline",
        enabled = snap.actions.canEdit,
        error = errors[LabField.DEADLINE],
        supporting = "Calendar date, YYYY-MM-DD. For example, 2026-12-31.",
    )
    LabField(
        value = model.form.terms,
        onValueChange = { model.onForm(model.form.copy(terms = it)) },
        label = "Agreement terms",
        enabled = snap.actions.canEdit,
        error = errors[LabField.TERMS],
        minLines = 4,
    )
    LabField(
        value = model.form.paymentConditions,
        onValueChange = { model.onForm(model.form.copy(paymentConditions = it)) },
        label = "Payment conditions (optional)",
        enabled = snap.actions.canEdit,
        error = errors[LabField.PAYMENT],
        minLines = 2,
    )
    Button(onClick = model::saveDraft, enabled = snap.actions.canEdit, modifier = Modifier.labButton()) {
        Text("Save draft")
    }
    Button(onClick = model::continueToReview, enabled = snap.actions.canEdit, modifier = Modifier.labButton()) {
        Text("Continue")
    }
    OutlinedButton(onClick = model::cancelDeal, enabled = snap.actions.canEdit, modifier = Modifier.labButton()) {
        Text("Cancel")
    }
}

@Composable
private fun ReviewBody(model: DealLabViewModel, snap: LabDealSnapshot) {
    val dirty = model.form.trimmed() != snap.form
    if (dirty) {
        Text(
            "The create form has details that are not in this version yet. Use Save draft or Continue to include them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        when {
            !snap.complete -> "Incomplete. Signing stays off until every required detail is valid."
            snap.localSigned || snap.simulatedSigned -> "Signed version. These details are locked. Editing would break the signatures, so editing is turned off."
            else -> "Editable draft. Nothing is signed. The hash covers the details on this page."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    InfoCard("Title", snap.form.title.ifBlank { "Not entered" })
    InfoCard("Party A", partyLine(snap.form.partyAName, snap.form.partyARole, simulated = false))
    InfoCard("Party B, simulated", partyLine(snap.form.partyBName, snap.form.partyBRole, simulated = true))
    InfoCard(
        "Amount",
        if (snap.amountMinor != null && snap.currency != null) {
            "${InrMoney.format(snap.amountMinor)} · ${snap.currency}"
        } else {
            snap.form.amountText.ifBlank { "Not entered" }
        },
    )
    InfoCard("Deadline", snap.form.deadline.ifBlank { "Not entered" })
    InfoCard("Terms", snap.form.terms.ifBlank { "Not entered" })
    InfoCard(
        "Payment conditions",
        snap.form.paymentConditions.ifBlank { "None. This is optional." },
    )
    InfoCard(
        "Document hash",
        snap.documentHashHex ?: "No document hash yet. Finish the required details first.",
    )
    if (snap.problems.isNotEmpty()) {
        InfoCard("Still needed", snap.problems.joinToString("\n"))
    }
    Text(
        "The hash is SHA-256 of the canonical agreement, including the title, names, roles, amount in paise, deadline, terms, and payment conditions.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = model::useForSignatures,
        enabled = snap.actions.canPropose,
        modifier = Modifier.labButton(),
    ) { Text("Use this version for signatures") }
    if (!snap.actions.canPropose && snap.complete && !snap.localSigned && !snap.simulatedSigned && snap.status == DealStatusNames.PROPOSED) {
        Text(
            "This version is ready to sign. Open Sign and sign each party yourself. Nothing is signed automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SignBody(model: DealLabViewModel, snap: LabDealSnapshot) {
    if (!snap.complete) {
        Text(
            "This agreement is incomplete, so both signature buttons are off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    } else if (!snap.actions.canSignLocal && !snap.actions.canSignSimulated && !snap.localSigned && !snap.simulatedSigned) {
        Text(
            "Signing is off until you review this version and choose Use this version for signatures.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    InfoCard(
        "Party A",
        signatureCopy(
            signed = snap.localSigned,
            signedText = DealLabController.LOCAL_SIGN,
            waiting = "Not signed. The button below creates a real Ed25519 signature for the local synthetic party. It is not a legal eSign.",
            at = snap.localSignedAtEpochMs,
        ),
    )
    Button(
        onClick = model::signLocal,
        enabled = snap.actions.canSignLocal,
        modifier = Modifier.labButton(),
    ) { Text("Sign as Party A") }
    InfoCard(
        "Party B, simulated",
        signatureCopy(
            signed = snap.simulatedSigned,
            signedText = DealLabController.SIMULATED_SIGN,
            waiting = "Not signed. Party B is simulated. The button uses a second key on this device and does not contact anyone.",
            at = snap.simulatedSignedAtEpochMs,
        ),
    )
    Button(
        onClick = model::signSimulated,
        enabled = snap.actions.canSignSimulated,
        modifier = Modifier.labButton(),
    ) { Text("Sign as Party B (simulated)") }
    Text(
        if (snap.persisted && snap.finalityProven) {
            "Both signatures verified. The agreement is ciphertext in the local Armor Deal database."
        } else if (snap.persisted) {
            "Stored finality evidence does not verify. The saved row was not changed."
        } else {
            "Nothing is written to the database until you sign as both parties. Neither party is signed for you."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun BackupBody(model: DealLabViewModel, snap: LabDealSnapshot) {
    Text(
        "Cloud backup is unavailable. This demo does not send the agreement to Cloudflare or anywhere else.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "A local recovery package is an encrypted file kept in this session. It counts as verified only after the app opens that same package with the secret.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        if (snap.backupVerified) DealLabController.VERIFIED_BACKUP else "Local backup is not verified.",
        style = MaterialTheme.typography.titleSmall,
    )
    model.secretHex?.let { hex ->
        Text("Synthetic demo secret", style = MaterialTheme.typography.labelLarge)
        Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    OutlinedButton(onClick = model::newSecret, modifier = Modifier.labButton()) {
        Text("Generate synthetic 32-byte secret")
    }
    Button(onClick = model::seal, enabled = snap.actions.canSealBackup, modifier = Modifier.labButton()) {
        Text("Create local recovery package")
    }
    Button(onClick = model::verifyCurrent, enabled = snap.actions.canVerifyBackup, modifier = Modifier.labButton()) {
        Text("Verify by opening the package")
    }
    OutlinedButton(onClick = model::verifyWrong, enabled = snap.actions.canVerifyBackup, modifier = Modifier.labButton()) {
        Text("Try a different secret")
    }
    OutlinedButton(onClick = model::refuseExecute, modifier = Modifier.labButton()) {
        Text("Try to mark executed")
    }
    Text(
        "This agreement is not executed. Execution needs cloud receipts from both parties, and this demo does not create those receipts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HistoryBody(model: DealLabViewModel, dealId: String, tick: Int) {
    val events = androidx.compose.runtime.remember(tick, dealId) { model.controller.history(dealId) }
    if (events.isEmpty()) {
        Text("No audit events yet.")
        return
    }
    events.forEach { event ->
        InfoCard(
            title = "${event.summary()} · ${event.outcome}",
            body = buildString {
                append(formatWhen(event.createdAtEpochMs))
                if (event.detail.isNotBlank()) {
                    append("\n")
                    append(event.detail)
                }
            },
        )
    }
}

@Composable
private fun StoredPage(model: DealLabViewModel) {
    val review = model.controller.storedReview
    if (review == null) {
        Text("Stored deal could not be shown.")
        return
    }
    Text("Synthetic demo saved on this device.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
    InfoCard("Status", review.statusLabel)
    InfoCard("Title", review.title ?: "This saved agreement uses the earlier format and has no title.")
    InfoCard("Party A", partyLine(review.partyAName.orEmpty(), review.partyARole.orEmpty(), simulated = false))
    InfoCard("Party B, simulated", partyLine(review.partyBName.orEmpty(), review.partyBRole.orEmpty(), simulated = true))
    InfoCard(
        "Amount",
        if (review.amountMinor != null && review.currency != null) {
            "${InrMoney.format(review.amountMinor)} · ${review.currency}"
        } else {
            "No amount in this saved document."
        },
    )
    InfoCard("Deadline", review.deadline ?: "No deadline in this saved document.")
    InfoCard("Terms", review.terms)
    InfoCard("Payment conditions", review.paymentConditions?.ifBlank { "None. This is optional." } ?: "None recorded.")
    InfoCard("Document hash", review.documentHashHex ?: "No document hash.")
    Text(
        if (review.backupVerified) {
            DealLabController.VERIFIED_BACKUP
        } else {
            "Local backup is not verified. Cloud backup is unavailable, and no execution is claimed."
        },
        style = MaterialTheme.typography.titleSmall,
    )
    review.audits.forEach { event ->
        InfoCard(event.summary(), event.detail.ifBlank { formatWhen(event.createdAtEpochMs) })
    }
}

@Composable
private fun LabField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: String?,
    supporting: String? = null,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        minLines = minLines,
        isError = error != null,
        label = { Text(label) },
        supportingText = {
            Text(error ?: supporting.orEmpty())
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                body,
                style = if (title == "Document hash") {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            )
        }
    }
}

private fun Modifier.labButton(): Modifier = fillMaxWidth().heightIn(min = 48.dp)

private fun partyLine(name: String, role: String, simulated: Boolean): String {
    val who = name.ifBlank { "Name not entered" }
    val what = role.ifBlank { "Role not entered" }
    val extra = if (simulated) " Simulated party. No remote person was contacted." else " Local synthetic party."
    return "$who, $what.$extra"
}

private fun signatureCopy(signed: Boolean, signedText: String, waiting: String, at: Long?): String {
    if (!signed) return "$waiting\nStatus: Not signed."
    return "$signedText\nStatus: Signed.\nTime: ${formatWhen(at)}"
}

private fun formatWhen(epoch: Long?): String {
    if (epoch == null) return "Time not recorded."
    val formatted = Instant.ofEpochMilli(epoch)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss"))
    return formatted
}
