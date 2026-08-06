/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.sample.viewsjava;

import io.devconsole.api.BrowserEndpoint;
import io.devconsole.api.DevConsoleState;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.StateFlow;

/**
 * Thin seam between {@link MainActivity} (compiled into every build type) and the DevConsole
 * Start/Stop launcher panel. {@code sdk:ui-views} (the module that provides the real panel,
 * {@code DevConsolePanelView}) has no release no-op counterpart, so it is a
 * {@code debugImplementation}-only dependency -- see {@code build.gradle.kts}. MainActivity
 * depends on this interface rather than on {@code io.devconsole.ui.views.DevConsolePanelView}
 * directly, so it compiles unchanged in both build types; {@link DevConsolePanelControllerFactory}
 * (a debug-only and a release-only implementation of the same class name) supplies the real panel
 * or a no-op accordingly.
 */
interface DevConsolePanelController {
    Job bind(
            StateFlow<DevConsoleState> state,
            CoroutineScope scope,
            Function0<Unit> onStart,
            Function0<Unit> onStop);

    void setEndpoint(BrowserEndpoint endpoint);
}
