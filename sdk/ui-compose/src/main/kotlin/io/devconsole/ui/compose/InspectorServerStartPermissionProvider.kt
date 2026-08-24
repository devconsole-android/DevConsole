/**
 * Optional server-start permission preflight for the SDK-owned inspector UI.
 *
 * This stays separate from [InspectorDataSource] so adding the feature does not add a method to a
 * long-lived public interface that existing Java or Kotlin host adapters already implement. A
 * runtime that needs a permission prompt implements this capability; all other adapters continue
 * to start through [InspectorDataSource.setServerRunning] unchanged.
 */
package io.devconsole.ui.compose

interface InspectorServerStartPermissionProvider {
    /**
     * Returns the Android runtime permission required before the SDK UI may start the server, or
     * `null` when the requested start can proceed immediately. Host/API starts are unaffected.
     */
    fun serverStartPermission(): String?
}
