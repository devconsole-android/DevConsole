# Keep the local server engine's fully-qualified name so the Gradle variant-policy plugin's
# byte-scan signature ("Lio/devconsole/server/ktor/KtorLocalServerEngine;") survives R8/minification
# in a consuming app and the "full runtime leaked into a protected variant" check still fires.
-keep class io.devconsole.server.ktor.KtorLocalServerEngine { *; }
