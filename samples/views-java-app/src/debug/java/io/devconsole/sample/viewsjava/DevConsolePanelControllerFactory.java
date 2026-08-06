/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.sample.viewsjava;

import android.view.ViewGroup;
import android.widget.FrameLayout;

import io.devconsole.api.BrowserEndpoint;
import io.devconsole.api.DevConsoleState;
import io.devconsole.ui.views.DevConsolePanelView;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.StateFlow;

/**
 * Debug-only factory: inflates the real DevConsole Start/Stop launcher panel
 * ({@code DevConsolePanelView} from {@code sdk:ui-views}, a
 * {@code debugImplementation}-only dependency) into the host container. Paired with the
 * release-build stub of the same class name (see {@code src/release}) so {@link MainActivity}
 * never references {@code DevConsolePanelView} directly and compiles unchanged in both build
 * types.
 */
final class DevConsolePanelControllerFactory {
    private DevConsolePanelControllerFactory() {}

    static DevConsolePanelController create(FrameLayout container) {
        DevConsolePanelView view = new DevConsolePanelView(container.getContext(), null);
        container.addView(
                view,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new DevConsolePanelController() {
            @Override
            public Job bind(
                    StateFlow<DevConsoleState> state,
                    CoroutineScope scope,
                    Function0<Unit> onStart,
                    Function0<Unit> onStop) {
                return view.bind(state, scope, onStart, onStop);
            }

            @Override
            public void setEndpoint(BrowserEndpoint endpoint) {
                view.setEndpoint(endpoint);
            }
        };
    }
}
