package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.MockRulePersistence
import io.devconsole.mocks.MockScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidMockRuleStoreTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val preferencesName = "mock-rule-test-${UUID.randomUUID()}"

    @Test
    fun `round trips every supported durable mock action and compatibility metadata`() {
        val store = AndroidMockRuleStore(application, preferencesName)
        val persistence =
            MockRulePersistence(
                enabled = false,
                createdAppVersion = "1",
                installationId = "install-a",
                compatibleAppVersions = setOf("2", "3"),
                compatibleAcrossReinstall = true,
            )
        val actions =
            listOf(
                MockAction.StaticResponse(201, "created", mapOf("X-Mock" to "true")),
                MockAction.TemplateResponse(202, "{{path}}", mapOf("Content-Type" to "text/plain")),
                MockAction.Delay(25, MockAction.StatusOverride(204)),
                MockAction.ConnectionFailure("offline"),
                MockAction.Timeout(100),
                MockAction.BodyReplacement("replacement"),
                MockAction.Passthrough,
            )
        val rules =
            actions.mapIndexed { index, action ->
                MockRule(
                    id = "rule-$index",
                    priority = index,
                    method = "POST",
                    scheme = "https",
                    host = "api.test",
                    path = "/orders/$index",
                    queryPredicates = mapOf("page" to "$index"),
                    headerPredicates = mapOf("X-Tenant" to "alpha"),
                    bodyPredicate = "accepted",
                    scope = MockScope.PERSISTENT_INTERNAL,
                    action = action,
                ).withPersistence(persistence)
            }

        store.save(rules)
        val restored = store.load()

        assertEquals(rules, restored)
        assertEquals(actions, restored.map(MockRule::action))
        assertEquals(persistence, restored.first().persistence)
    }

    @Test
    fun `installation id is stable and malformed storage fails closed to no rules`() {
        val store = AndroidMockRuleStore(application, preferencesName)
        val installationId = store.installationId()
        application
            .getSharedPreferences(preferencesName, Application.MODE_PRIVATE)
            .edit()
            .putString("rules", "not-json")
            .commit()

        assertEquals(installationId, AndroidMockRuleStore(application, preferencesName).installationId())
        assertTrue(store.load().isEmpty())
        assertTrue(
            application
                .getSharedPreferences(preferencesName, Application.MODE_PRIVATE)
                .getBoolean("rules_corrupt", false),
        )
    }
}
