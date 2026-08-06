/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.sample.viewsjava;

import android.widget.FrameLayout;

import io.devconsole.api.BrowserEndpoint;
import io.devconsole.api.DevConsoleState;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.StateFlow;

/**
 * Release-build stub. {@code DevConsolePanelView} (from {@code sdk:ui-views}) is a
 * {@code debugImplementation}-only dependency (no release no-op counterpart exists for it), so it
 * is never on the release classpath. This keeps {@link MainActivity} source-identical across
 * build types -- release runs on {@code sdk:noop}, so Start/Stop is never reachable here anyway.
 * The container passed in is intentionally left empty; {@code bind} returns {@code null}, which
 * {@link MainActivity#onDestroy} already null-checks before use.
 */
final class DevConsolePanelControllerFactory {
    private DevConsolePanelControllerFactory() {}

    static DevConsolePanelController create(FrameLayout container) {
        return new DevConsolePanelController() {
            @Override
            public Job bind(
                    StateFlow<DevConsoleState> state,
                    CoroutineScope scope,
                    Function0<Unit> onStart,
                    Function0<Unit> onStop) {
                return null;
            }

            @Override
            public void setEndpoint(BrowserEndpoint endpoint) {
                // No-op: no panel view in release builds.
            }
        };
    }
}
