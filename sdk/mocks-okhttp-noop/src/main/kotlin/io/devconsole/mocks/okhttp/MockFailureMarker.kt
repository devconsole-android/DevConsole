/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.mocks.okhttp

class MockFailureMarker(
    val tags: Map<String, String>,
) : RuntimeException("DevConsole mock failure", null, false, false)
