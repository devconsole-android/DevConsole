package io.devconsole

import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.CaptureCategory
import io.devconsole.api.DevConsoleConfig
import io.devconsole.mocks.MockDecision
import io.devconsole.mocks.MockEngineRegistry
import io.devconsole.mocks.MockRequest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `installDevConsole` wires mock rules from whatever engine is in [MockEngineRegistry], which is only
 * useful if the enabled facade actually puts one there. Without this, the default mock setup would
 * silently do nothing: the installer would read `null` on every host and quietly skip the
 * interceptor, exactly the inert Mocks screen the default exists to fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockEnginePublishingTest {
    @After fun tearDown() = MockEngineRegistry.clear()

    @Test
    fun `initialize publishes the facade's own engine`() {
        MockEngineRegistry.clear()
        val provider = PlatformFacadeProvider()

        provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

        assertSame(provider.mockEngine(), MockEngineRegistry.active())
    }

    /**
     * A host that turned [CaptureCategory.MOCKS] off must not get mocking wired in through the back
     * door. `mockEngine()` already answers with a permanently disabled engine in that case, so
     * publishing its result -- rather than the live instance -- is what keeps the gate honest.
     */
    @Test
    fun `the published engine respects a disabled MOCKS category`() {
        MockEngineRegistry.clear()
        val provider = PlatformFacadeProvider()

        provider.initialize(
            ApplicationProvider.getApplicationContext(),
            DevConsoleConfig.default().withCaptureCategories(CaptureCategory.all() - CaptureCategory.MOCKS),
        )

        val published = MockEngineRegistry.active()
        assertSame(provider.mockEngine(), published)
        assertTrue(
            "a disabled engine must pass every request through",
            published?.decide(MockRequest("GET", "https", "api.test", "/orders")) is MockDecision.Passthrough,
        )
    }
}
