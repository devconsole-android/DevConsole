/**
 * @author Shakib
 * @since 19/10/26
 */
package io.devconsole.server.ktor

import io.devconsole.api.BrowserSecurity
import io.devconsole.api.EditingCapabilities
import io.devconsole.api.NetworkProtection
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.server.application.Application

/** Explicit opt-ins for tests of authentication, redaction, budgets, and read-only gates. */
internal fun Application.protectedDevConsoleModule(
    sessionAuthority: SessionAuthority,
    sessionCodeAuthority: SessionCodeAuthority = SessionCodeAuthority(sessionAuthority),
    configure: DevConsoleModuleConfig.() -> Unit = {},
) {
    devConsoleModule(sessionAuthority, sessionCodeAuthority) {
        browserSecurity = BrowserSecurity.SESSION_CODE
        networkProtection = NetworkProtection.recommended()
        redactionPolicy = RedactionPolicy.recommended()
        val editing = EditingCapabilities.readOnly()
        mocksEditable = editing.mocks
        captureRulesEditable = editing.captureRules
        featureFlagsEditable = editing.featureFlags
        preferencesEditable = editing.preferences
        databaseEditable = editing.database
        filesEditable = editing.files
        composerEnabled = false
        composerHostsEditable = false
        stateMutationsEnabled = false
        configure()
    }
}
