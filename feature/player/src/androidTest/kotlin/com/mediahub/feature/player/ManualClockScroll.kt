package com.mediahub.feature.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/**
 * Compose 1.7.6 performScrollTo repeatedly starts an animated ScrollBy without a loop bound.
 * With autoAdvance=false its animation receives no frames, so an offscreen target can spin
 * forever. Use the same user-accessible action once per step, then explicitly drive its frames.
 */
internal fun SemanticsNodeInteraction.performScrollToWithClock(
    rule: ComposeContentTestRule,
): SemanticsNodeInteraction {
    val startedAt = SystemClock.elapsedRealtime()
    var previous: ScrollGeometry? = null
    var unchangedSteps = 0
    var latest = "target not fetched"
    repeat(MAX_SCROLL_STEPS) { step ->
        check(SystemClock.elapsedRealtime() - startedAt < SCROLL_TIMEOUT_MS) {
            "Timed out scrolling after $step steps: $latest"
        }
        rule.mainClock.advanceTimeByFrame()
        val target = fetchSemanticsNode("Bounded accessibility scroll target is missing")
        val tag = target.config.getOrNull(SemanticsProperties.TestTag) ?: "node-${target.id}"
        val scroller = generateSequence(target.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Target $tag has no vertical accessibility scroll ancestor")
        val root = generateSequence(scroller) { it.parent }.last()
        val targetBounds = Rect(target.positionInRoot, target.size.toSize())
        // boundsInRoot is clipped by Compose ancestors. Also intersect the actual root: a
        // partially expanded modal's logical scroll container may extend below its visible window.
        val viewport = scroller.boundsInRoot.intersect(root.boundsInRoot)
        val axis = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
        val geometry = ScrollGeometry(targetBounds, viewport, axis.value(), axis.maxValue())
        latest = "target=$tag step=$step $geometry"
        check(viewport.width > 0f && viewport.height > 0f) { "Empty visible scroll viewport: $latest" }
        check(targetBounds.height <= viewport.height + PIXEL_TOLERANCE) {
            "Full target cannot fit the visible viewport; expand the real sheet first: $latest"
        }
        if (viewport.fullyContains(targetBounds) &&
            target.boundsInRoot.width >= targetBounds.width - PIXEL_TOLERANCE &&
            target.boundsInRoot.height >= targetBounds.height - PIXEL_TOLERANCE
        ) {
            Log.i(LOG_TAG, "visible: $latest")
            return assertIsDisplayed()
        }

        val last = previous
        unchangedSteps = if (last != null && geometry.approximatelyEquals(last)) unchangedSteps + 1 else 0
        check(unchangedSteps < MAX_UNCHANGED_STEPS) {
            "ScrollBy made no visible progress after explicit animation frames: $latest; previous=$last"
        }
        previous = geometry

        val verticalDelta = when {
            targetBounds.top < viewport.top - PIXEL_TOLERANCE -> targetBounds.top - viewport.top
            targetBounds.bottom > viewport.bottom + PIXEL_TOLERANCE -> targetBounds.bottom - viewport.bottom
            else -> error("Target is clipped horizontally or by another ancestor, not vertically scrollable: $latest")
        }.coerceIn(-viewport.height * SCROLL_STEP_FRACTION, viewport.height * SCROLL_STEP_FRACTION)
        val semanticDelta = if (axis.reverseScrolling) -verticalDelta else verticalDelta
        Log.i(LOG_TAG, "ScrollBy dy=$semanticDelta: $latest")
        // Like Compose's own scrollToNode, use the fresh ancestor's production semantics action.
        // Do not call performScrollTo here: its inner loop would prevent the explicit clock step.
        rule.runOnUiThread {
            val action = checkNotNull(scroller.config[SemanticsActions.ScrollBy].action) {
                "Missing ScrollBy action: $latest"
            }
            check(action(0f, semanticDelta)) { "ScrollBy rejected the user action: $latest" }
        }
        rule.mainClock.advanceTimeBy(SCROLL_SETTLE_MS)
    }
    error("Could not fully reveal target within $MAX_SCROLL_STEPS accessibility scrolls: $latest")
}

/**
 * Wait for the actual entrance/expansion, not merely for a visible sliver of the modal.
 * In Material3 1.3.1 Hidden can expose Collapse once partial anchors exist, so neither an empty
 * Expand query nor Collapse alone proves completion. Require a stable, fully visible viewport.
 */
internal fun expandVisualSheetWithClock(rule: ComposeContentTestRule) {
    val expand = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
    val collapse = SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)
    val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
    val startedAt = SystemClock.elapsedRealtime()
    var previous: SheetGeometry? = null
    var stableSamples = 0
    var expansionRequested = false
    var latest = "sheet has not entered"
    repeat(MAX_SHEET_STEPS) { step ->
        check(SystemClock.elapsedRealtime() - startedAt < SCROLL_TIMEOUT_MS) {
            "Modal entrance/expansion timed out: $latest"
        }
        rule.mainClock.advanceTimeBy(50)
        val previews = rule.onAllNodesWithTag(PlayerVisualTestTags.PREVIEW).fetchSemanticsNodes()
        val handles = rule.onAllNodes(dismiss, useUnmergedTree = true).fetchSemanticsNodes()
        val expandCount = rule.onAllNodes(expand, useUnmergedTree = true).fetchSemanticsNodes().size
        val collapseCount = rule.onAllNodes(collapse, useUnmergedTree = true).fetchSemanticsNodes().size
        check(previews.size <= 1 && handles.size <= 1 && expandCount <= 1 && collapseCount <= 1) {
            "Ambiguous visual sheet: previews=${previews.size}, handles=${handles.size}, " +
                "expand=$expandCount, collapse=$collapseCount"
        }
        if (previews.isEmpty() || handles.isEmpty()) {
            previous = null
            stableSamples = 0
            latest = "step=$step previews=${previews.size} handles=${handles.size}"
            return@repeat
        }
        val preview = previews.single()
        val scroller = generateSequence(preview.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        if (scroller == null) {
            previous = null
            stableSamples = 0
            latest = "step=$step preview exists but scroll semantics are not attached yet"
            return@repeat
        }
        val root = generateSequence(scroller) { it.parent }.last().boundsInRoot
        val fullViewport = Rect(scroller.positionInRoot, scroller.size.toSize())
        val visibleViewport = scroller.boundsInRoot.intersect(root)
        val handle = handles.single()
        val handleBounds = Rect(handle.positionInRoot, handle.size.toSize())
        val geometry = SheetGeometry(
            fullViewport, visibleViewport, handleBounds, preview.size.height.toFloat(),
            expandCount == 1, collapseCount == 1,
        )
        val last = previous
        stableSamples = if (last != null && geometry.approximatelyEquals(last)) stableSamples + 1 else 0
        previous = geometry
        val handleVisible = handleBounds.width > 0f && handleBounds.height > 0f &&
            root.fullyContains(handleBounds) &&
            handle.boundsInRoot.height >= handleBounds.height - PIXEL_TOLERANCE
        latest = "step=$step stable=$stableSamples requested=$expansionRequested $geometry"
        if (!handleVisible || stableSamples < REQUIRED_STABLE_SHEET_SAMPLES) return@repeat

        if (geometry.canExpand && !expansionRequested) {
            rule.onNode(expand, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.Expand) { action ->
                    check(action()) { "The real modal sheet rejected Expand: $latest" }
                }
            expansionRequested = true
            previous = null
            stableSamples = 0
            Log.i(LOG_TAG, "sheet Expand action dispatched: $latest")
            return@repeat
        }
        // Both production editors are tall sheets with a partial anchor. Collapse is required
        // in addition to full geometry; during Hidden it can exist but this geometry cannot.
        if (!geometry.canExpand && geometry.canCollapse &&
            visibleViewport.fullyContains(fullViewport) &&
            visibleViewport.width > 0f &&
            visibleViewport.height >= geometry.previewHeight - PIXEL_TOLERANCE
        ) {
            Log.i(LOG_TAG, "sheet entrance and expansion settled: $latest")
            return
        }
    }
    error("Modal never reached a stable expanded visible viewport: $latest")
}

private data class SheetGeometry(
    val fullViewport: Rect,
    val visibleViewport: Rect,
    val handle: Rect,
    val previewHeight: Float,
    val canExpand: Boolean,
    val canCollapse: Boolean,
) {
    fun approximatelyEquals(other: SheetGeometry): Boolean =
        fullViewport.approximatelyEquals(other.fullViewport) &&
            visibleViewport.approximatelyEquals(other.visibleViewport) &&
            handle.approximatelyEquals(other.handle) &&
            abs(previewHeight - other.previewHeight) < PIXEL_TOLERANCE &&
            canExpand == other.canExpand && canCollapse == other.canCollapse
}

private fun Rect.approximatelyEquals(other: Rect): Boolean =
    abs(left - other.left) < PIXEL_TOLERANCE && abs(top - other.top) < PIXEL_TOLERANCE &&
        abs(right - other.right) < PIXEL_TOLERANCE && abs(bottom - other.bottom) < PIXEL_TOLERANCE

private data class ScrollGeometry(
    val target: Rect,
    val viewport: Rect,
    val position: Float,
    val maximum: Float,
) {
    fun approximatelyEquals(other: ScrollGeometry): Boolean =
        abs(target.top - other.target.top) < PIXEL_TOLERANCE &&
            abs(target.bottom - other.target.bottom) < PIXEL_TOLERANCE &&
            abs(viewport.top - other.viewport.top) < PIXEL_TOLERANCE &&
            abs(viewport.bottom - other.viewport.bottom) < PIXEL_TOLERANCE &&
            abs(position - other.position) < PIXEL_TOLERANCE &&
            abs(maximum - other.maximum) < PIXEL_TOLERANCE
}

private fun Rect.fullyContains(other: Rect): Boolean =
    other.left >= left - PIXEL_TOLERANCE && other.right <= right + PIXEL_TOLERANCE &&
        other.top >= top - PIXEL_TOLERANCE && other.bottom <= bottom + PIXEL_TOLERANCE

private const val LOG_TAG = "VisualClockScroll"
private const val MAX_SCROLL_STEPS = 20
private const val MAX_SHEET_STEPS = 160
private const val REQUIRED_STABLE_SHEET_SAMPLES = 3
private const val MAX_UNCHANGED_STEPS = 2
private const val SCROLL_TIMEOUT_MS = 15_000L
private const val SCROLL_SETTLE_MS = 1_000L
private const val SCROLL_STEP_FRACTION = 0.8f
private const val PIXEL_TOLERANCE = 1f
