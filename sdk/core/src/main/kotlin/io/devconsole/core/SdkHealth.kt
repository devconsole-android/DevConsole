package io.devconsole.core

import io.devconsole.api.DevConsoleState

data class SdkHealth(
    val initializationCount: Long = 0,
    val publishedEventCount: Long = 0,
    val droppedEventCount: Long = 0,
    val state: DevConsoleState = DevConsoleState.Uninitialized,
    val lastFailure: String? = null,
)
