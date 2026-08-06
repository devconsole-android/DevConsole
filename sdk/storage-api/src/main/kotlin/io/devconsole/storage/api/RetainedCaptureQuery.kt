package io.devconsole.storage.api

/** Session selection and durable capture reads shared by inspector surfaces. */
class RetainedCaptureQuery(
    private val store: () -> EventStore?,
    private val currentSessionId: () -> String?,
) {
    /**
     * [pluginIds] empty (the default) preserves this method's original contract exactly: an
     * explicit [sessionId] wins, otherwise it falls back to whatever session is current right now.
     * That fallback is correct for a *live* read (e.g. the Android in-app inspector's own Crashes
     * tab, always asking about the run it is embedded in) but wrong for a developer asking "show me
     * every crash" after the process that hit one has already died and a new session is current --
     * so a non-empty [pluginIds] with no explicit [sessionId] instead reads across every retained
     * session via [EventStore.recentEventsForPlugins], ignoring [currentSessionId] entirely.
     */
    suspend fun events(
        sessionId: String? = null,
        limit: Int = DEFAULT_LIMIT,
        pluginIds: Set<String> = emptySet(),
    ): List<StoredEvent> {
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        val resolvedSessionId = sessionId ?: currentSessionId()
        val unsorted =
            when {
                sessionId == null && pluginIds.isNotEmpty() -> store()?.recentEventsForPlugins(pluginIds, limit)
                resolvedSessionId != null -> store()?.recentEventsForSession(resolvedSessionId, limit, pluginIds)
                else -> null
            }
        return unsorted.orEmpty().sortedWith(compareBy(StoredEvent::wallTimeMs).thenBy(StoredEvent::sequence))
    }

    /** Full-session reads are reserved for explicitly bounded export generation. */
    suspend fun eventsForExport(sessionId: String? = null): List<StoredEvent> {
        val resolved = sessionId ?: currentSessionId() ?: return emptyList()
        return store()
            ?.eventsForSession(resolved)
            .orEmpty()
            .sortedWith(compareBy(StoredEvent::wallTimeMs).thenBy(StoredEvent::sequence))
    }

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 500
    }
}
