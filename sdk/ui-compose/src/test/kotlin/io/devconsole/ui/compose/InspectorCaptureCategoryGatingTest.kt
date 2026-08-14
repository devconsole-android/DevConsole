/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

import io.devconsole.api.CaptureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM coverage for [InspectorState.captures]/[InspectorState.visibleObserveTabs]/
 * [InspectorState.visibleDestinations] -- the one place the "a disabled capture category hides its
 * surface" rule lives, verified here without any Compose runtime so it can run on every commit, not
 * just an instrumented one.
 */
class InspectorCaptureCategoryGatingTest {
    @Test
    fun `only SOCKET and MQTT enabled shows exactly the Sockets tab and hides the Data destination`() {
        val state = InspectorState(captureCategories = setOf(CaptureCategory.SOCKET, CaptureCategory.MQTT))

        assertEquals(listOf(ObserveTab.SOCKETS), state.visibleObserveTabs())
        assertTrue(InspectorDestination.DATA !in state.visibleDestinations())
        // OBSERVE stays visible: it has at least one visible tab (SOCKETS). CONTROL/MORE are never
        // category-gated at the destination level.
        assertEquals(
            listOf(InspectorDestination.OBSERVE, InspectorDestination.CONTROL, InspectorDestination.MORE),
            state.visibleDestinations(),
        )
    }

    @Test
    fun `every category enabled shows every tab and every destination`() {
        // A registered provider as well as every category: REMOTE_CONFIG is the one tab that also
        // needs data to exist, so "every category on" alone is no longer enough to show them all.
        val state =
            InspectorState(
                captureCategories = CaptureCategory.all(),
                remoteConfig = listOf(InspectorRemoteConfigUi(id = "firebase")),
            )

        assertEquals(ObserveTab.entries, state.visibleObserveTabs())
        assertEquals(InspectorDestination.entries, state.visibleDestinations())
    }

    @Test
    fun `the Remote Config tab needs a registered provider as well as the STATE category`() {
        val provider = listOf(InspectorRemoteConfigUi(id = "firebase"))

        // STATE on but nothing registered: the tab must not exist. InspectorTabRow splits its width
        // equally, so an always-present sixth tab would narrow the other five in every app that has
        // no Remote Config at all -- which, with STATE on by default, is most of them.
        assertTrue(
            ObserveTab.REMOTE_CONFIG !in
                InspectorState(captureCategories = setOf(CaptureCategory.STATE)).visibleObserveTabs(),
        )
        // A provider registered but STATE off: still hidden, the same way the route is gated.
        assertTrue(
            ObserveTab.REMOTE_CONFIG !in
                InspectorState(captureCategories = setOf(CaptureCategory.NETWORK), remoteConfig = provider)
                    .visibleObserveTabs(),
        )
        assertEquals(
            listOf(ObserveTab.REMOTE_CONFIG),
            InspectorState(captureCategories = setOf(CaptureCategory.STATE), remoteConfig = provider)
                .visibleObserveTabs(),
        )
    }

    @Test
    fun `a provider that reports nothing at all still gets the tab`() {
        // "Fetched nothing" and "never fetched" are answers you came to this surface for, so a
        // provider with no entries -- or one that could not be read -- must not read as "no Remote
        // Config". Only *no provider registered* hides the tab.
        val empty = listOf(InspectorRemoteConfigUi(id = "firebase", entries = emptyList()))
        val unavailable = listOf(InspectorRemoteConfigUi(id = "firebase", unavailableReason = "no such class"))

        assertTrue(
            ObserveTab.REMOTE_CONFIG in
                InspectorState(captureCategories = setOf(CaptureCategory.STATE), remoteConfig = empty)
                    .visibleObserveTabs(),
        )
        assertTrue(
            ObserveTab.REMOTE_CONFIG in
                InspectorState(captureCategories = setOf(CaptureCategory.STATE), remoteConfig = unavailable)
                    .visibleObserveTabs(),
        )
    }

    @Test
    fun `an empty category set hides every tab and every gated destination without crashing`() {
        val state = InspectorState(captureCategories = emptySet())

        assertTrue(state.visibleObserveTabs().isEmpty())
        // OBSERVE has no visible tabs left, so it drops out too; DATA needs INSPECTION, which is
        // off. CONTROL and MORE are unconditional -- MORE in particular must never disappear, since
        // it is the one destination a stranded operator can always fall back to.
        assertEquals(listOf(InspectorDestination.CONTROL, InspectorDestination.MORE), state.visibleDestinations())
        assertTrue(InspectorDestination.MORE in state.visibleDestinations())
    }

    @Test
    fun `captures reflects membership in captureCategories directly`() {
        val state = InspectorState(captureCategories = setOf(CaptureCategory.MOCKS))

        assertTrue(state.captures(CaptureCategory.MOCKS))
        assertTrue(!state.captures(CaptureCategory.STATE))
    }

    @Test
    fun `SOCKET alone or MQTT alone is enough to keep the Sockets tab visible`() {
        assertEquals(
            listOf(ObserveTab.SOCKETS),
            InspectorState(captureCategories = setOf(CaptureCategory.SOCKET)).visibleObserveTabs(),
        )
        assertEquals(
            listOf(ObserveTab.SOCKETS),
            InspectorState(captureCategories = setOf(CaptureCategory.MQTT)).visibleObserveTabs(),
        )
    }
}
