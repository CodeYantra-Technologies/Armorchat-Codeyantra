package com.codeyantra.armorchat.deal.origin

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codeyantra.armorchat.deal.transport.DealConsentBlock
import com.codeyantra.armorchat.deal.transport.DealConsentChecklist
import com.codeyantra.armorchat.deal.transport.DealConsentGate
import com.codeyantra.armorchat.deal.transport.DealDocumentPresenter
import com.codeyantra.armorchat.deal.transport.DealSignConfirmation
import com.codeyantra.armorchat.deal.transport.DealSigningControls
import com.codeyantra.armorchat.deal.transport.DealViewerState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.codeyantra.armorchat.BuildConfig
import com.codeyantra.armorchat.core.security.TinkEd25519IdentityKeyStore
import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.AndroidKeystoreDealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.draft.DealDraftEditor
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.draft.DealDrafts
import com.codeyantra.armorchat.deal.draft.OpenedLocalDraft
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.transport.DealSigningSnapshot
import com.codeyantra.armorchat.deal.transport.DealSigningLookup
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.repository.SendTextResult
import com.codeyantra.armorchat.messaging.ChatMessagingService
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object ChatDealDestination {
    const val ROUTE = "debug/chat_deal/{conversationId}"

    fun route(conversationId: String): String = "debug/chat_deal/$conversationId"
}

fun NavGraphBuilder.installChatDealDestination(navController: NavHostController) {
    composable(
        route = ChatDealDestination.ROUTE,
        arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
    ) { entry ->
        ChatDealDraftRoute(
            conversationId = entry.arguments?.getString("conversationId").orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }
}

@Composable
fun ChatDealTopBarAction(onCreate: (() -> Unit)?) {
    if (onCreate == null) return
    if (!ChatDealFactory.enabled(BuildConfig.DEBUG, DealFeature.PRODUCTION_UI_ENABLED)) return
    IconButton(onClick = onCreate) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = "Create a local deal draft",
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatDealDraftEntryPoint {
    fun identityRepository(): IdentityRepository
    fun conversationRepository(): ConversationRepository
    fun contactRepository(): ContactRepository
    fun identityKeyStore(): TinkEd25519IdentityKeyStore
    fun messaging(): ChatMessagingService
}

@Composable
fun ChatDealDraftRoute(conversationId: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val model: ChatDealDraftModel = viewModel(
        factory = viewModelFactory {
            initializer { ChatDealDraftModel(context, conversationId) }
        },
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) model.persistOnLeave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ChatDealDraftScreen(
        model = model,
        onBack = {
            if (model.persistOnLeave()) onBack()
        },
        onLeaveWithoutSaving = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDealDraftScreen(
    model: ChatDealDraftModel,
    onBack: () -> Unit,
    onLeaveWithoutSaving: () -> Unit,
) {
    val state by model.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Local deal draft") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = state.publicStatus,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Debug only. Not a government eSign. This phone signs only with its own deal key. Terms stay inside the encrypted session.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            state.missing?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(text = message, color = colors.error)
                return@Column
            }
            if (state.loading) {
                Spacer(Modifier.height(16.dp))
                Text("Loading the conversation.", color = colors.onSurfaceVariant)
                return@Column
            }
            Spacer(Modifier.height(12.dp))
            Text(state.partyALine, style = MaterialTheme.typography.bodyMedium)
            Text(state.partyBLine, style = MaterialTheme.typography.bodyMedium)
            state.signing?.let { signing ->
                SigningPanel(signing, state, model)
            }
            if (state.signing != null) {
                state.notice?.let { notice ->
                    Text(text = notice, color = colors.error, modifier = Modifier.padding(top = 8.dp))
                }
                TextButton(onClick = { model.refreshSigning() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh signing status")
                }
                TextButton(onClick = onLeaveWithoutSaving, modifier = Modifier.fillMaxWidth()) {
                    Text("Leave")
                }
                return@Column
            }
            Text(
                "Contractual roles are not Party A or Party B. A legal name in the draft is not a signature.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.notice?.let { notice ->
                Text(
                    text = notice,
                    color = colors.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            DraftField("Agreement title", state.title, model::onTitle)
            DraftField("Purpose", state.purpose, model::onPurpose)
            DraftField("Your contractual role", state.proposerRole, model::onProposerRole)
            DraftField("Other contractual role", state.counterpartyRole, model::onCounterpartyRole)
            DraftField("Terms and obligations", state.terms, model::onTerms)
            DraftField("Payment conditions", state.paymentConditions, model::onPayment)
            DraftField("Currency", state.currency, model::onCurrency)
            DraftField("Amount in minor units", state.amountText, model::onAmount)
            DraftField("Deadline (YYYY-MM-DD)", state.deadlineDate, model::onDeadline)
            DraftField("Effective date (optional)", state.effectiveDate, model::onEffectiveDate)
            DraftField("Your legal name (optional)", state.partyALegalName, model::onLegalName)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { model.persistOnLeave() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save local draft")
            }
            if (state.draftId != null && state.status == DealDrafts.STATUS_LOCAL) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { model.cancelDraft(onLeaveWithoutSaving) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel local draft")
                }
            }
            if (state.draftId != null && state.status == DealDrafts.STATUS_LOCAL) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { model.sendProposal() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Send encrypted proposal")
                }
            }
            TextButton(onClick = onLeaveWithoutSaving, modifier = Modifier.fillMaxWidth()) {
                Text("Leave")
            }
        }
    }
}

@Composable
private fun SigningPanel(
    signing: DealSigningSnapshot,
    state: DraftScreenState,
    model: ChatDealDraftModel,
) {
    val colors = MaterialTheme.colorScheme
    Spacer(Modifier.height(12.dp))
    Text(
        "Signing stage: ${signing.stage.name}",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        if (signing.proven) "Finality: proven for this version hash." else "Finality: not proven.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    Text(
        "Role on this phone: ${signing.role.name}",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    // Phase 5.4C: the verified document is the only signing source shown here.
    val viewer = DealDocumentPresenter.present(signing)
    val controls = DealSigningControls.from(signing, viewer)
    Spacer(Modifier.height(8.dp))
    Text("Agreement document", style = MaterialTheme.typography.labelLarge)
    when (viewer) {
        is DealViewerState.Failed -> Text(viewer.message, color = colors.error, style = MaterialTheme.typography.bodyMedium)
        is DealViewerState.Rendered -> {
            Text(
                "${viewer.view.title ?: "Untitled"} · version ${viewer.view.versionId} · ${viewer.view.fingerprint}",
                style = MaterialTheme.typography.bodyMedium,
            )
            viewer.hiddenWarning?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Text(
        when {
            signing.proven -> "Signed document. Signing controls are disabled."
            signing.ownReviewConsent -> "You recorded review consent for this exact version."
            else -> "Read the complete agreement and record review consent before you review, confirm your name or sign."
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { model.openViewer(true) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (signing.proven) "Open the signed agreement" else "Read the complete agreement")
    }
    if (state.viewerOpen) {
        DocumentViewerDialog(viewer, signing, state, model)
    }
    if (signing.canReview && !signing.proven) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = { model.review() }, enabled = controls.review, modifier = Modifier.fillMaxWidth()) {
            Text("I reviewed this proposal")
        }
    }
    if (signing.canConfirmName && !signing.proven) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.confirmName,
            onValueChange = model::onConfirmName,
            label = { Text("Your legal name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { model.confirmName() }, enabled = controls.confirmName, modifier = Modifier.fillMaxWidth()) {
            Text("Confirm my legal name")
        }
    }
    if (signing.canSign && !signing.proven) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = { model.openSignConfirm(true) }, enabled = controls.sign, modifier = Modifier.fillMaxWidth()) {
            Text("Sign with this phone's deal key")
        }
    }
    val signView = (viewer as? DealViewerState.Rendered)?.view
    if (state.signConfirmOpen && signView != null && controls.sign) {
        val confirmation = DealSignConfirmation.from(signView, signing.role)
        AlertDialog(
            onDismissRequest = { model.openSignConfirm(false) },
            title = { Text(confirmation.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(confirmation.body, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    model.openSignConfirm(false)
                    model.sign()
                }) { Text("Sign this version") }
            },
            dismissButton = {
                TextButton(onClick = { model.openSignConfirm(false) }) { Text("Cancel") }
            },
        )
    }
    if (controls.revise) {
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.revisionTerms,
            onValueChange = model::onRevisionTerms,
            label = { Text("Revised terms") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { model.revise() }, modifier = Modifier.fillMaxWidth()) {
            Text("Send a new unsigned version")
        }
    }
    if (controls.reject) {
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { model.reject() }, modifier = Modifier.fillMaxWidth()) {
            Text("Reject this agreement")
        }
    }
}

/** Phase 5.4C: full-screen, fully expanded, scrollable document. Nothing collapsed or truncated. */
@Composable
private fun DocumentViewerDialog(
    viewer: DealViewerState,
    signing: DealSigningSnapshot,
    state: DraftScreenState,
    model: ChatDealDraftModel,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = { model.openViewer(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text("Complete agreement", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { model.openViewer(false) }) { Text("Close") }
                when (viewer) {
                    is DealViewerState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(viewer.message, color = colors.error, style = MaterialTheme.typography.bodyLarge)
                    }
                    is DealViewerState.Rendered -> {
                        viewer.hiddenWarning?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = colors.error, style = MaterialTheme.typography.bodyLarge)
                        }
                        var section = ""
                        for (line in viewer.lines) {
                            if (line.section != section) {
                                section = line.section
                                Spacer(Modifier.height(16.dp))
                                Text(section, style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(line.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                            Text(line.value, style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(20.dp))
                        val block = DealConsentGate.block(viewer, state.consent, signing.ownReviewConsent, signing.proven)
                        if (block == DealConsentBlock.FINAL) {
                            Text("This version is fully signed. Signing controls are disabled.", style = MaterialTheme.typography.bodyMedium)
                        } else if (block == DealConsentBlock.ALREADY_RECORDED) {
                            Text("Review consent is recorded for this exact version.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val checkable = block != DealConsentBlock.HIDDEN_CHARACTERS
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = state.consent.checked,
                                        enabled = checkable,
                                        role = Role.Checkbox,
                                        onValueChange = { model.onReviewChecked(it) },
                                    ),
                            ) {
                                Checkbox(checked = state.consent.checked, onCheckedChange = null, enabled = checkable)
                                Text(DealConsentGate.CHECKBOX_TEXT, style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { model.recordReview() },
                                enabled = block == DealConsentBlock.NONE,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Record review consent for this version")
                            }
                        }
                    }
                }
                state.notice?.let { Text(it, color = colors.error, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DraftField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

private data class DraftScreenState(
    val loading: Boolean = true,
    val missing: String? = null,
    val notice: String? = null,
    val publicStatus: String = DealDrafts.PUBLIC_LOCAL,
    val status: String? = null,
    val draftId: String? = null,
    val partyALine: String = "",
    val partyBLine: String = "",
    val partyAName: String = "",
    val partyBName: String = "",
    val title: String = "",
    val purpose: String = "",
    val proposerRole: String = "",
    val counterpartyRole: String = "",
    val terms: String = "",
    val paymentConditions: String = "",
    val currency: String = "",
    val amountText: String = "",
    val deadlineDate: String = "",
    val effectiveDate: String = "",
    val partyALegalName: String = "",
    val signing: DealSigningSnapshot? = null,
    val confirmName: String = "",
    val revisionTerms: String = "",
    val consent: DealConsentChecklist = DealConsentChecklist(),
    val viewerOpen: Boolean = false,
    val signConfirmOpen: Boolean = false,
) {
    override fun toString(): String = "DraftScreenState"

    fun editor(): DealDraftEditor {
        val amountRaw = amountText.trim()
        val currencyRaw = currency.trim().uppercase(Locale.US)
        val amount = when {
            amountRaw.isEmpty() && currencyRaw.isEmpty() -> null
            else -> amountRaw.toLongOrNull() ?: throw DealFormatException("Amount must be a whole number of minor units.")
        }
        val currencyCode = currencyRaw.ifEmpty { null }
        return DealDraftEditor(
            title = title.trim(),
            purpose = purpose.trim(),
            proposerRole = proposerRole.trim(),
            counterpartyRole = counterpartyRole.trim(),
            terms = terms,
            paymentConditions = paymentConditions.trim(),
            currency = currencyCode,
            amountMinor = amount,
            deadlineDate = deadlineDate.trim(),
            effectiveDate = effectiveDate.trim(),
            partyALegalName = partyALegalName.trim(),
        )
    }
}

private class ChatDealDraftModel(
    application: Application,
    private val conversationId: String,
) : AndroidViewModel(application) {
    private val entry = EntryPointAccessors.fromApplication(application, ChatDealDraftEntryPoint::class.java)
    private val database = DealDatabase.open(application)
    private val protector = AndroidKeystoreDealKeyProtector()
    private val store = DealDraftStore(
        database = database,
        protector = protector,
        debugBuild = BuildConfig.DEBUG,
    )
    private val stateFlow = MutableStateFlow(DraftScreenState())
    val state: StateFlow<DraftScreenState> = stateFlow
    private var local: UserIdentity? = null
    private var conversation: Conversation? = null
    private var contact: Contact? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    fun onTitle(value: String) = edit { it.copy(title = value) }
    fun onPurpose(value: String) = edit { it.copy(purpose = value) }
    fun onProposerRole(value: String) = edit { it.copy(proposerRole = value) }
    fun onCounterpartyRole(value: String) = edit { it.copy(counterpartyRole = value) }
    fun onTerms(value: String) = edit { it.copy(terms = value) }
    fun onPayment(value: String) = edit { it.copy(paymentConditions = value) }
    fun onCurrency(value: String) = edit { it.copy(currency = value) }
    fun onAmount(value: String) = edit { it.copy(amountText = value) }
    fun onDeadline(value: String) = edit { it.copy(deadlineDate = value) }
    fun onEffectiveDate(value: String) = edit { it.copy(effectiveDate = value) }
    fun onLegalName(value: String) = edit { it.copy(partyALegalName = value) }
    fun onConfirmName(value: String) = edit { it.copy(confirmName = value) }
    fun onRevisionTerms(value: String) = edit { it.copy(revisionTerms = value) }

    fun refreshSigning() {
        viewModelScope.launch(Dispatchers.IO) { reloadSigning() }
    }

    fun sendProposal() {
        val draftId = stateFlow.value.draftId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!persistOnLeave()) return@launch
            val id = stateFlow.value.draftId ?: draftId
            act("The proposal was not sent.") {
                entry.messaging().sendDealDraft(conversationId, id)
            }
        }
    }

    fun review() = act("The review was not sent.") { entry.messaging().reviewDeal(conversationId) }

    fun confirmName() {
        val name = stateFlow.value.confirmName.trim()
        if (name.isEmpty()) {
            stateFlow.update { it.copy(notice = "Enter your own legal name.") }
            return
        }
        act("The name confirmation was not sent.") { entry.messaging().confirmDealName(conversationId, name) }
    }

    fun sign() = act("The signature was not sent.") { entry.messaging().signDeal(conversationId) }

    fun openViewer(open: Boolean) = stateFlow.update { it.copy(viewerOpen = open) }

    fun openSignConfirm(open: Boolean) = stateFlow.update { it.copy(signConfirmOpen = open) }

    /** Ticking the box never records consent by itself. */
    fun onReviewChecked(value: Boolean) = stateFlow.update { current ->
        current.copy(consent = current.consent.check(value, DealDocumentPresenter.present(current.signing)))
    }

    /** Records consent only after the explicit action, a rendered document and a ticked box for this exact version. */
    fun recordReview() {
        val current = stateFlow.value
        val signing = current.signing ?: return
        val viewer = DealDocumentPresenter.present(signing)
        if (DealConsentGate.block(viewer, current.consent, signing.ownReviewConsent, signing.proven) != DealConsentBlock.NONE) {
            stateFlow.update { it.copy(notice = "Read the complete agreement and tick the box for this exact version first.") }
            return
        }
        act("The review consent was not recorded.") { entry.messaging().recordDealReview(conversationId) }
    }

    fun revise() {
        val terms = stateFlow.value.revisionTerms
        if (terms.isBlank()) {
            stateFlow.update { it.copy(notice = "Enter the revised terms.") }
            return
        }
        act("The revision was not sent.") { entry.messaging().reviseDeal(conversationId, terms) }
    }

    fun reject() = act("The rejection was not sent.") { entry.messaging().rejectDeal(conversationId, "Declined.") }

    /** Saves a valid editor. Returns false when the current text must stay on screen. */
    fun persistOnLeave(): Boolean {
        val snapshot = stateFlow.value
        if (snapshot.signing != null) return true
        if (snapshot.loading || snapshot.missing != null) return true
        val editor = try {
            snapshot.editor()
        } catch (e: DealFormatException) {
            stateFlow.update { it.copy(notice = e.message) }
            return snapshot.draftId == null && snapshot.title.isBlank() && snapshot.terms.isBlank()
        }
        val identity = local
        val chat = conversation
        val person = contact
        return try {
            val opened = if (snapshot.draftId == null) {
                store.createFromChat(
                    local = identity,
                    conversation = chat,
                    contact = person,
                    editor = editor,
                    localDealKeys = DealKeyMaterial.generate(),
                    localSigner = signerFor(identity),
                    typedPartyBId = null,
                )
            } else {
                store.update(snapshot.draftId, editor, identity, chat, person)
            }
            stateFlow.value = opened.toState(snapshot)
            true
        } catch (e: DealException) {
            stateFlow.update { it.copy(notice = e.message) }
            false
        }
    }

    fun cancelDraft(onDone: () -> Unit) {
        val id = stateFlow.value.draftId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.cancel(id)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: DealException) {
                stateFlow.update { it.copy(notice = e.message) }
            }
        }
    }

    private suspend fun load() {
        try {
            val identity = entry.identityRepository().getIdentity()
            val chat = entry.conversationRepository().getConversation(conversationId)
            val person = chat?.let { entry.contactRepository().getContact(it.contactId) }
            if (identity == null || chat == null || person == null || chat.contactId != person.id) {
                val message = when {
                    identity == null -> "The local ArmorChat identity is missing."
                    chat == null -> "The conversation is missing."
                    else -> "The conversation contact is missing."
                }
                stateFlow.value = DraftScreenState(loading = false, missing = message)
                return
            }
            local = identity
            conversation = chat
            contact = person
            val opened = store.active(chat.id)
            val base = opened?.toState(blankParties(identity, person)) ?: blankParties(identity, person)
            val signing = lookupSigning(identity, person)
            stateFlow.value = base.withPinnedParties(signing, identity, person).copy(signing = signing)
        } catch (e: DealException) {
            stateFlow.value = DraftScreenState(loading = false, missing = e.message)
        }
    }

    private fun signerFor(identity: UserIdentity?): DealMessagingSigner {
        if (identity == null) throw DealFormatException("The local ArmorChat identity is missing.")
        val publicKey = try {
            Base64Url.decode(identity.publicIdentityKey)
        } catch (e: IllegalArgumentException) {
            throw DealFormatException("Messaging public key is not valid.")
        }
        return KeystoreDealBindingSigner(entry.identityKeyStore(), publicKey)
    }

    private fun act(failure: String, block: suspend () -> SendTextResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                block()
            } catch (e: DealException) {
                stateFlow.update { it.copy(notice = failure) }
                return@launch
            }
            val notice = when (result) {
                is SendTextResult.Queued -> null
                else -> failure
            }
            reloadSigning()
            stateFlow.update { it.copy(notice = notice) }
        }
    }

    private fun reloadSigning() {
        val identity = local ?: return
        val person = contact ?: return
        val signing = try {
            lookupSigning(identity, person)
        } catch (e: DealException) {
            null
        }
        stateFlow.update { current ->
            current.withPinnedParties(signing, identity, person).copy(
                signing = signing,
                publicStatus = signing?.stage?.name ?: current.publicStatus,
                consent = current.consent.forState(DealDocumentPresenter.present(signing)),
            )
        }
    }

    private fun lookupSigning(identity: UserIdentity, person: Contact): DealSigningSnapshot? {
        val localKey = Base64Url.decode(identity.publicIdentityKey)
        val peerKey = Base64Url.decode(person.publicIdentityKey)
        return DealSigningLookup.find(database, protector, localKey, peerKey)
    }

    private fun edit(block: (DraftScreenState) -> DraftScreenState) {
        stateFlow.update { current ->
            if (current.loading || current.missing != null) current else block(current)
        }
    }
}

private class KeystoreDealBindingSigner(
    private val keystore: TinkEd25519IdentityKeyStore,
    override val publicKey: ByteArray,
) : DealMessagingSigner {
    override fun sign(payload: ByteArray): ByteArray = runBlocking {
        keystore.signDealIdentityBinding(payload)
    }
}

private fun blankParties(local: UserIdentity, contact: Contact) = DraftScreenState(
    loading = false,
    partyAName = local.displayName,
    partyBName = contact.displayName,
    partyALine = partyLine("Party A", local.displayName, local.armorChatId),
    partyBLine = partyLine("Party B", contact.displayName, contact.armorChatId),
    publicStatus = DealDrafts.PUBLIC_LOCAL,
)

private fun OpenedLocalDraft.toState(previous: DraftScreenState): DraftScreenState = DraftScreenState(
    loading = false,
    publicStatus = publicStatus,
    status = status,
    draftId = draftId,
    partyAName = previous.partyAName,
    partyBName = previous.partyBName,
    partyALine = partyLine("Party A", previous.partyAName, origin.partyA.armorChatId),
    partyBLine = partyLine("Party B", previous.partyBName, origin.partyB.armorChatId),
    title = editor.title,
    purpose = editor.purpose,
    proposerRole = editor.proposerRole,
    counterpartyRole = editor.counterpartyRole,
    terms = editor.terms,
    paymentConditions = editor.paymentConditions,
    currency = editor.currency.orEmpty(),
    amountText = editor.amountMinor?.toString().orEmpty(),
    deadlineDate = editor.deadlineDate,
    effectiveDate = editor.effectiveDate,
    partyALegalName = editor.partyALegalName,
)

private fun partyLine(role: String, name: String, armorChatId: String): String =
    DealPartyLabels.line(role, name, armorChatId)

/**
 * Once a bound deal exists, Party A/B come from its identity pins
 * (PROPOSER = Party A, COUNTERPARTY = Party B) on every phone.
 */
private fun DraftScreenState.withPinnedParties(
    signing: DealSigningSnapshot?,
    local: UserIdentity,
    contact: Contact,
): DraftScreenState {
    if (signing == null || signing.proposerArmorChatId.isEmpty() || signing.counterpartyArmorChatId.isEmpty()) {
        return this
    }
    val lines = DealPartyLabels.fromPins(
        proposerArmorChatId = signing.proposerArmorChatId,
        counterpartyArmorChatId = signing.counterpartyArmorChatId,
        localArmorChatId = local.armorChatId,
        localDisplayName = local.displayName,
        contactArmorChatId = contact.armorChatId,
        contactDisplayName = contact.displayName,
    )
    return copy(
        partyALine = lines.partyA,
        partyBLine = lines.partyB,
        partyAName = lines.partyAName,
        partyBName = lines.partyBName,
    )
}
