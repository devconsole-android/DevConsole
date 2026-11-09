package io.devconsole

import androidx.core.content.FileProvider

/**
 * The SDK's own [FileProvider] subclass, whose only reason to exist is a unique `android:name` in
 * the merged manifest.
 *
 * The manifest merger keys `<provider>` nodes by `android:name`, not by authority, so declaring
 * `androidx.core.content.FileProvider` directly made this provider and a host-declared one the same
 * node. Any host that already shares files -- camera capture, image picking, an attachment picker --
 * then failed its own debug build with a merge conflict on `android:authorities` and on the nested
 * `android.support.FILE_PROVIDER_PATHS` resource, and the merger's suggested `tools:replace` fix
 * silently dropped this SDK's authority instead (see FAQ_TROUBLESHOOTING.md). A name no host would
 * pick removes the collision at the source; the authority itself is unchanged.
 */
internal class DevConsoleFileProvider : FileProvider()
