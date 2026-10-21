package com.codeyantra.armorchat.deal

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.fail
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.codeyantra.armorchat.deal.lab.DealLabController
import com.codeyantra.armorchat.deal.lab.DealLabDestination
import com.codeyantra.armorchat.deal.lab.DealLabScreen
import com.codeyantra.armorchat.deal.lab.DealLabViewModel
import com.codeyantra.armorchat.deal.lab.LabPage
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.domain.model.ThemeMode
import com.codeyantra.armorchat.ui.theme.ArmorChatTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp", application = Application::class)
class DealLabLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun narrowScreenShowsTheWarningAndKeepsStepsInside() {
        showCreate(compose)
        assertLabFits(compose, createTargets)
    }
}

private val createTargets = listOf(
    "Create",
    "Review",
    "Sign",
    "Backup",
    "History",
    "Save draft",
    "Continue",
    "Cancel",
    "Back",
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = Application::class)
class DealLabA20sLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a20sWidthShowsTheWarningAndKeepsStepsInside() {
        showCreate(compose)
        assertLabFits(compose, createTargets)
    }

    @Test
    fun signButtonsStayOffUntilTheUserChoosesThem() {
        val controller = labController()
        val model = DealLabViewModel(controller)
        val id = controller.createDraft("Alpha terms")
        model.open(controller.summaries().first { it.dealId == id })
        model.go(LabPage.SIGN)
        compose.setContent {
            ArmorChatTheme(themeMode = ThemeMode.DARK) {
                DealLabScreen(model = model, onBack = {})
            }
        }
        compose.onNodeWithText(DealLabDestination.BANNER).assertIsDisplayed()
        compose.onNodeWithText("Sign as Party A").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Sign as Party B (simulated)").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        assertFalse(controller.snapshot(id).localSigned)
        assertFalse(controller.snapshot(id).simulatedSigned)
        assertLabFits(
            compose,
            listOf("Create", "Review", "Sign", "Backup", "History", "Sign as Party A", "Sign as Party B (simulated)", "Back"),
        )
    }
}

private fun showCreate(compose: ComposeContentTestRule) {
    val model = DealLabViewModel(labController())
    model.newDeal()
    compose.setContent {
        ArmorChatTheme(themeMode = ThemeMode.DARK) {
            DealLabScreen(model = model, onBack = {})
        }
    }
    compose.onNodeWithText(DealLabDestination.BANNER).assertIsDisplayed()
    compose.onNodeWithText("Synthetic demo. Not a real agreement.").assertIsDisplayed()
    listOf("Create", "Review", "Sign", "Backup", "History", "Agreement title", "Save draft", "Continue", "Cancel").forEach { label ->
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }
}

private fun assertLabFits(compose: ComposeContentTestRule, targets: List<String>) {
    compose.waitForIdle()
    val root = compose.onRoot().fetchSemanticsNode()
    fun walk(node: SemanticsNode) {
        val bounds = node.boundsInRoot
        if (bounds.width > 1f && bounds.height > 1f) {
            assertTrue("Node extends past the right edge: $bounds", bounds.right <= root.boundsInRoot.right + 1f)
            assertTrue("Node starts past the left edge: $bounds", bounds.left >= root.boundsInRoot.left - 1f)
        }
        node.children.forEach { walk(it) }
    }
    walk(root)
    targets.forEach { label -> assertTouchTarget(compose, label) }
}

private fun assertTouchTarget(compose: ComposeContentTestRule, label: String) {
    val minTouch = with(compose.density) { 48.dp.toPx() }
    val rootWidth = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
    val start = if (label == "Back") {
        compose.onNodeWithContentDescription(label).fetchSemanticsNode()
    } else {
        compose.onNodeWithText(label).fetchSemanticsNode()
    }
    var current: SemanticsNode? = start
    while (current != null) {
        val height = maxOf(current.boundsInRoot.height, current.size.height.toFloat())
        val clickable = current.config.getOrNull(SemanticsActions.OnClick) != null
        if (clickable && height + 0.5f >= minTouch) {
            val right = current.positionInRoot.x + current.size.width
            assertTrue(
                "$label extends past the right edge at $right of $rootWidth",
                right <= rootWidth + 1f,
            )
            assertTrue(
                "$label starts past the left edge at ${current.positionInRoot.x}",
                current.positionInRoot.x >= -1f,
            )
            return
        }
        current = current.parent
    }
    fail("No 48dp touch target found for $label")
}

private fun labController(): DealLabController {
    val context = RuntimeEnvironment.getApplication()
    val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    return DealLabController(
        repository = DealRepository(database, clock = { 1_700_000_000_000L }),
        protector = SoftwareDealKeyProtector(ByteArray(32) { 1 }),
        clock = { 1_700_000_000_000L },
    )
}
