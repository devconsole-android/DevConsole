package io.devconsole.mocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MockEngineTest {
    @Test fun `rejects invalid mock response header values during upsert`() {
        val engine = MockEngine(emptyList())
        val result =
            runCatching {
                engine.upsert(
                    MockRule(
                        "invalid",
                        1,
                        action =
                            MockAction.StaticResponse(
                                200,
                                "body",
                                mapOf(
                                    "X-Note" to "café",
                                ),
                            ),
                    ),
                )
            }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Invalid mock response header value"))
        assertTrue(engine.rules().isEmpty())
    }

    @Test
    fun `mock engine is enabled by default`() {
        assertTrue(MockEngine(emptyList()).isEnabled())
    }

    @Test fun `chooses exact-path rule over wildcard at equal priority`() {
        val engine = MockEngine(listOf(MockRule("wild", 1, path = "/.*"), MockRule("exact", 1, path = "/orders")))
        assertEquals(
            "exact",
            (engine.decide(MockRequest("GET", "https", "api.test", "/orders")) as MockDecision.Matched).rule.id,
        )
    }

    @Test fun `master switch fails open to passthrough`() {
        val engine = MockEngine(listOf(MockRule("rule", 1, path = "/orders")), enabled = false)
        assertTrue(engine.decide(MockRequest("GET", "https", "api.test", "/orders")) is MockDecision.Passthrough)
    }

    @Test fun `matches query header and body predicates before selecting a rule`() {
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        id = "admin-order",
                        priority = 2,
                        path = "/orders",
                        queryPredicates = mapOf("mode" to "admin"),
                        headerPredicates = mapOf("X-Tenant" to "alpha"),
                        bodyPredicate = "\\{\\\"approved\\\":true\\}",
                    ),
                ),
                bodyMatchingEnabled = true,
            )

        val match =
            engine.decide(
                MockRequest(
                    method = "POST",
                    scheme = "https",
                    host = "api.test",
                    path = "/orders",
                    query = mapOf("mode" to "admin"),
                    headers = mapOf("x-tenant" to "alpha"),
                    body = "{\"approved\":true}",
                ),
            )

        assertEquals("admin-order", (match as MockDecision.Matched).rule.id)
    }

    @Test fun `resolves request placeholders in a templated response`() {
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        "template",
                        1,
                        path = "/orders",
                        action =
                            MockAction.TemplateResponse(
                                200,
                                "{\"path\":\"{{path}}\",\"tenant\":\"{{header.X-Tenant}}\"}",
                            ),
                    ),
                ),
            )

        val action =
            (
                engine.decide(
                    MockRequest("GET", "https", "api.test", "/orders", headers = mapOf("X-Tenant" to "alpha")),
                ) as MockDecision.Matched
            ).action

        assertEquals(MockAction.StaticResponse(200, "{\"path\":\"/orders\",\"tenant\":\"alpha\"}"), action)
    }

    @Test fun `invalid template fails open instead of affecting the host request`() {
        val outcomes = mutableListOf<MockOutcome>()
        val engine =
            MockEngine(listOf(MockRule("bad", 1, action = MockAction.TemplateResponse(200, "{{unknown.value}}"))))
                .withOutcomeSink(outcomes::add)

        assertTrue(engine.decide(MockRequest("GET", "https", "api.test", "/")) is MockDecision.Passthrough)
        assertEquals("bad", (outcomes.single() as MockOutcome.EvaluationError).ruleId)
    }

    @Test fun `body matching is opt-in and bounded`() {
        val rule = MockRule("body", 1, bodyPredicate = "accepted")
        val request = MockRequest("POST", "https", "api.test", "/", body = "accepted")
        assertTrue(MockEngine(listOf(rule)).decide(request) is MockDecision.Passthrough)
        assertTrue(MockEngine(listOf(rule), bodyMatchingEnabled = true).decide(request) is MockDecision.Matched)
        assertTrue(
            MockEngine(
                listOf(rule),
                bodyMatchingEnabled = true,
                maxBodyPredicateBytes = 4,
            ).decide(request) is MockDecision.Passthrough,
        )
    }

    @Test fun `asks adapters for a body only when a live rule has a body predicate`() {
        val plain = MockRule("plain", 1)
        val bodyRule = MockRule("body", 1, bodyPredicate = "accepted")
        assertEquals(null, MockEngine(listOf(bodyRule)).requestBodyLimitBytes())
        assertEquals(null, MockEngine(listOf(plain), bodyMatchingEnabled = true).requestBodyLimitBytes())
        val engine = MockEngine(listOf(plain, bodyRule), bodyMatchingEnabled = true, maxBodyPredicateBytes = 64)
        assertEquals(64, engine.requestBodyLimitBytes())
        engine.setEnabled(false)
        assertEquals(null, engine.requestBodyLimitBytes())
    }

    @Test fun `template renders a missing header or query value as empty instead of failing the rule`() {
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        "tpl",
                        1,
                        action =
                            MockAction.TemplateResponse(
                                200,
                                "ua=[{{header.User-Agent}}] page=[{{query.page}}] {{body}}",
                            ),
                    ),
                ),
            )
        val decision = engine.decide(MockRequest("GET", "https", "api.test", "/", headers = mapOf("X" to "{{path}}")))
        val action = (decision as MockDecision.Matched).action as MockAction.StaticResponse
        assertEquals("ua=[] page=[] ", action.body)
    }

    @Test fun `unknown template placeholder names itself in the evaluation error`() {
        val outcomes = mutableListOf<MockOutcome>()
        val engine =
            MockEngine(listOf(MockRule("typo", 1, action = MockAction.TemplateResponse(200, "{{pathh}}"))))
                .withOutcomeSink { outcomes += it }
        assertTrue(engine.decide(MockRequest("GET", "https", "api.test", "/")) is MockDecision.Passthrough)
        assertEquals(
            "Unknown template placeholder {{pathh}}",
            (outcomes.single() as MockOutcome.EvaluationError).message,
        )
    }

    @Test fun `clears only session-scoped rules`() {
        val engine =
            MockEngine(
                listOf(
                    MockRule("session", 2, path = "/orders", scope = MockScope.SESSION),
                    MockRule("fixture", 1, path = "/orders", scope = MockScope.TEST_FIXTURE),
                ),
            )

        engine.clearSessionRules()

        assertEquals(
            "fixture",
            (engine.decide(MockRequest("GET", "https", "api.test", "/orders")) as MockDecision.Matched).rule.id,
        )
    }

    @Test fun `supports atomic rule upsert listing and removal`() {
        val engine = MockEngine(emptyList())
        engine.upsert(MockRule("orders", 1, path = "/orders", action = MockAction.StaticResponse(200, "[]")))

        assertEquals("orders", engine.rules().single().id)
        assertEquals(
            "orders",
            (engine.decide(MockRequest("GET", "https", "api.test", "/orders")) as MockDecision.Matched).rule.id,
        )
        assertTrue(engine.remove("orders"))
        assertTrue(engine.rules().isEmpty())
    }

    @Test
    fun `rejects unsupported regex before a rule enters runtime state`() {
        val engine = MockEngine(emptyList())

        val result = runCatching { engine.upsert(MockRule("unsafe", 1, path = "/orders(?=/admin)")) }

        assertTrue(result.isFailure)
        assertTrue(engine.rules().isEmpty())
    }

    @Test
    fun `oversized path input fails open without regex evaluation`() {
        val engine = MockEngine(listOf(MockRule("wild", 1, path = ".*")))

        assertTrue(
            engine.decide(MockRequest("GET", "https", "api.test", "/".repeat(20_000))) is MockDecision.Passthrough,
        )
    }

    @Test
    fun `records the selected rule id and passthrough reason`() {
        val outcomes = mutableListOf<MockOutcome>()
        val engine =
            MockEngine(listOf(MockRule("orders", 1, path = "/orders")))
                .withOutcomeSink(outcomes::add)

        engine.decide(MockRequest("GET", "https", "api.test", "/orders"))
        engine.decide(MockRequest("GET", "https", "api.test", "/profile"))

        assertEquals("orders", (outcomes[0] as MockOutcome.Matched).ruleId)
        assertEquals(MockPassthroughReason.NO_MATCH, (outcomes[1] as MockOutcome.Passthrough).reason)
    }

    @Test
    fun `persistent rules restore disabled after version or install changes unless compatible`() {
        val store = InMemoryMockRuleStore()
        val compatible =
            MockRule("persistent", 1, path = "/orders", scope = MockScope.PERSISTENT_INTERNAL)
                .withPersistence(
                    MockRulePersistence(
                        createdAppVersion = "1",
                        installationId = "install-a",
                        compatibleAppVersions = setOf("2"),
                    ),
                )
        store.save(listOf(compatible))

        val sameVersion = MockEngine(emptyList()).apply { restore(store, "1", "install-a") }
        val compatibleVersion = MockEngine(emptyList()).apply { restore(store, "2", "install-a") }
        val changedInstall = MockEngine(emptyList()).apply { restore(store, "2", "install-b") }
        val incompatibleVersion = MockEngine(emptyList()).apply { restore(store, "3", "install-a") }

        assertTrue(sameVersion.decide(MockRequest("GET", "https", "api.test", "/orders")) is MockDecision.Matched)
        assertTrue(compatibleVersion.decide(MockRequest("GET", "https", "api.test", "/orders")) is MockDecision.Matched)
        assertTrue(
            changedInstall.decide(MockRequest("GET", "https", "api.test", "/orders")) is MockDecision.Passthrough,
        )
        assertTrue(
            incompatibleVersion.decide(MockRequest("GET", "https", "api.test", "/orders")) is MockDecision.Passthrough,
        )
        assertTrue(
            !changedInstall
                .rules()
                .single()
                .persistence.enabled,
        )
        assertTrue(
            !incompatibleVersion
                .rules()
                .single()
                .persistence.enabled,
        )
    }

    @Test
    fun `disabled persistent rules are excluded from conflict detection`() {
        val enabled = MockRule("enabled", 1, method = "GET", host = "api.test", path = "/orders")
        val disabled =
            MockRule("disabled", 2, method = "GET", host = "api.test", path = "/orders")
                .withPersistence(MockRulePersistence(enabled = false))

        assertTrue(MockEngine(listOf(enabled, disabled)).conflicts().isEmpty())
    }

    @Test
    fun `bound persistence saves durable CRUD atomically and excludes session rules`() {
        val store = InMemoryMockRuleStore()
        val engine = MockEngine(emptyList())
        engine.bindPersistence(store, currentAppVersion = "2", installationId = "install-a")

        engine.upsert(MockRule("session", 2, scope = MockScope.SESSION))
        engine.upsert(
            MockRule(
                "durable",
                1,
                path = "/orders",
                scope = MockScope.PERSISTENT_INTERNAL,
                action = MockAction.StaticResponse(201, "created"),
            ),
        )

        assertEquals(listOf("durable"), store.load().map(MockRule::id))
        assertEquals(
            "2",
            store
                .load()
                .single()
                .persistence.createdAppVersion,
        )
        assertEquals(
            "install-a",
            store
                .load()
                .single()
                .persistence.installationId,
        )

        assertTrue(engine.remove("durable"))
        assertTrue(store.load().isEmpty())
        assertEquals(listOf("session"), engine.rules().map(MockRule::id))
    }

    @Test
    fun `sourceBodySnapshot defaults null and copies through unrelated field changes`() {
        val rule = MockRule("orders", 1, path = "/orders", sourceBodySnapshot = """{"amount":10}""")

        assertEquals("""{"amount":10}""", rule.sourceBodySnapshot)
        assertEquals(null, MockRule("orders", 1).sourceBodySnapshot)
        assertEquals("""{"amount":10}""", rule.copy(priority = 5).sourceBodySnapshot)
        assertEquals("""{"amount":10}""", rule.withPersistence(MockRulePersistence(enabled = false)).sourceBodySnapshot)
    }

    @Test
    fun `sourceBodySnapshot is stripped before a durable rule reaches its store but stays in memory`() {
        val store = InMemoryMockRuleStore()
        val engine = MockEngine(emptyList())
        engine.bindPersistence(store, currentAppVersion = "1", installationId = "install-a")

        engine.upsert(
            MockRule(
                "durable",
                1,
                path = "/orders",
                scope = MockScope.PERSISTENT_INTERNAL,
                action = MockAction.StaticResponse(201, "created"),
                sourceBodySnapshot = """{"amount":10}""",
            ),
        )

        assertEquals("""{"amount":10}""", engine.rules().single { it.id == "durable" }.sourceBodySnapshot)
        assertEquals(null, store.load().single { it.id == "durable" }.sourceBodySnapshot)
    }

    @Test
    fun `decide increments hit stats only for the rule that actually matched`() {
        val engine =
            MockEngine(listOf(MockRule("orders", 1, path = "/orders"), MockRule("profile", 1, path = "/profile")))
        val before = System.currentTimeMillis()

        engine.decide(MockRequest("GET", "https", "api.test", "/orders"))
        engine.decide(MockRequest("GET", "https", "api.test", "/orders"))

        assertEquals(2L, engine.stats("orders").hitCount)
        assertTrue(engine.stats("orders").lastHitEpochMs!! >= before)
        assertEquals(MockRuleStats(), engine.stats("profile"))
        assertEquals(MockRuleStats(), engine.stats("unknown-rule"))
    }

    @Test
    fun `statsSnapshot reports hits across every rule that has ever matched`() {
        val engine = MockEngine(listOf(MockRule("orders", 1, path = "/orders")))

        engine.decide(MockRequest("GET", "https", "api.test", "/orders"))

        assertEquals(setOf("orders"), engine.statsSnapshot().keys)
        assertEquals(1L, engine.statsSnapshot().getValue("orders").hitCount)
    }

    @Test
    fun `removing a rule resets its stats but editing a rule keeps them`() {
        val engine = MockEngine(listOf(MockRule("orders", 1, path = "/orders")))
        engine.decide(MockRequest("GET", "https", "api.test", "/orders"))
        assertEquals(1L, engine.stats("orders").hitCount)

        engine.upsert(MockRule("orders", 2, path = "/orders"))
        assertEquals(1L, engine.stats("orders").hitCount)

        assertTrue(engine.remove("orders"))
        assertEquals(MockRuleStats(), engine.stats("orders"))
    }

    @Test
    fun `failed durable save leaves active rules unchanged`() {
        val failingStore =
            object : MockRuleStore {
                override fun load(): List<MockRule> = emptyList()

                override fun save(rules: List<MockRule>) {
                    error("disk full")
                }
            }
        val engine = MockEngine(emptyList())
        engine.bindPersistence(failingStore, currentAppVersion = "1", installationId = "install-a")

        val result =
            runCatching {
                engine.upsert(MockRule("durable", 1, scope = MockScope.PERSISTENT_INTERNAL))
            }

        assertTrue(result.isFailure)
        assertTrue(engine.rules().isEmpty())
    }

    @Test
    fun `serialized persistence keeps the later rule mutation on disk`() {
        val firstSaveStarted = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        val saveCount = AtomicInteger()
        val savedRules = AtomicReference<List<MockRule>>(emptyList())
        val store =
            object : MockRuleStore {
                override fun load(): List<MockRule> = savedRules.get()

                override fun save(rules: List<MockRule>) {
                    if (saveCount.incrementAndGet() == 1) {
                        firstSaveStarted.countDown()
                        check(releaseFirstSave.await(2, TimeUnit.SECONDS))
                    }
                    savedRules.set(rules)
                }
            }
        val engine = MockEngine(emptyList())
        engine.bindPersistence(store, currentAppVersion = "1", installationId = "install-a")

        val first = Thread { engine.upsert(MockRule("first", 1, scope = MockScope.PERSISTENT_INTERNAL)) }
        first.start()
        assertTrue(firstSaveStarted.await(2, TimeUnit.SECONDS))
        val second = Thread { engine.upsert(MockRule("second", 1, scope = MockScope.PERSISTENT_INTERNAL)) }
        second.start()
        releaseFirstSave.countDown()
        first.join(2_000)
        second.join(2_000)

        assertTrue(!first.isAlive)
        assertTrue(!second.isAlive)
        assertEquals(listOf("first", "second"), savedRules.get().map(MockRule::id))
    }

    @Test
    fun `sequence non-loop sticks to last step after exhausting steps`() {
        val step0 = MockAction.StaticResponse(503, "first")
        val step1 = MockAction.StaticResponse(200, "second")
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        "seq",
                        1,
                        path = "/orders",
                        action = MockAction.Sequence(listOf(step0, step1), loop = false),
                    ),
                ),
            )
        val request = MockRequest("GET", "https", "api.test", "/orders")

        assertEquals(step0, (engine.decide(request) as MockDecision.Matched).action)
        assertEquals(step1, (engine.decide(request) as MockDecision.Matched).action)
        assertEquals(step1, (engine.decide(request) as MockDecision.Matched).action)
    }

    @Test
    fun `sequence loop wraps around to first step`() {
        val step0 = MockAction.StaticResponse(500, "fail")
        val step1 = MockAction.StaticResponse(200, "ok")
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        "seq-loop",
                        1,
                        path = "/orders",
                        action = MockAction.Sequence(listOf(step0, step1), loop = true),
                    ),
                ),
            )
        val request = MockRequest("GET", "https", "api.test", "/orders")

        assertEquals(step0, (engine.decide(request) as MockDecision.Matched).action)
        assertEquals(step1, (engine.decide(request) as MockDecision.Matched).action)
        assertEquals(step0, (engine.decide(request) as MockDecision.Matched).action)
        assertEquals(step1, (engine.decide(request) as MockDecision.Matched).action)
    }

    @Test
    fun `sequence counter resets on upsert removal and clearSessionRules`() {
        val step0 = MockAction.StaticResponse(503, "first")
        val step1 = MockAction.StaticResponse(200, "second")
        val rule =
            MockRule(
                "seq",
                1,
                path = "/orders",
                scope = MockScope.SESSION,
                action = MockAction.Sequence(listOf(step0, step1), loop = false),
            )
        val engine = MockEngine(listOf(rule))
        val request = MockRequest("GET", "https", "api.test", "/orders")

        assertEquals(step0, (engine.decide(request) as MockDecision.Matched).action)
        assertEquals(step1, (engine.decide(request) as MockDecision.Matched).action)

        engine.upsert(rule.copy(priority = 2))
        assertEquals(step0, (engine.decide(request) as MockDecision.Matched).action)

        engine.clearSessionRules()
        engine.upsert(rule)
        assertEquals(step0, (engine.decide(request) as MockDecision.Matched).action)
    }

    @Test
    fun `resolves nested template inside sequence step and throttle next`() {
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        "nested",
                        1,
                        path = "/orders",
                        action =
                            MockAction.Sequence(
                                listOf(
                                    MockAction.TemplateResponse(200, "step:{{path}}"),
                                    MockAction.Throttle(
                                        bytesPerSecond = 1_024,
                                        latencyMs = 10,
                                        next = MockAction.TemplateResponse(201, "throttled:{{method}}"),
                                    ),
                                ),
                            ),
                    ),
                ),
            )
        val request = MockRequest("POST", "https", "api.test", "/orders")

        assertEquals(
            MockAction.StaticResponse(200, "step:/orders"),
            (engine.decide(request) as MockDecision.Matched).action,
        )
        assertEquals(
            MockAction.Throttle(
                bytesPerSecond = 1_024,
                latencyMs = 10,
                next = MockAction.StaticResponse(201, "throttled:POST"),
            ),
            (engine.decide(request) as MockDecision.Matched).action,
        )
    }

    @Test
    fun `validates sequence mapRemote throttle init and nested headers`() {
        assertTrue(runCatching { MockAction.Sequence(emptyList()) }.isFailure)
        assertTrue(
            runCatching {
                MockAction.Sequence(List(21) { MockAction.Passthrough })
            }.isFailure,
        )
        assertTrue(
            runCatching {
                MockAction.Sequence(listOf(MockAction.Sequence(listOf(MockAction.Passthrough))))
            }.isFailure,
        )

        assertTrue(runCatching { MockAction.MapRemote() }.isFailure)
        assertTrue(runCatching { MockAction.MapRemote(scheme = "ftp") }.isFailure)
        assertTrue(runCatching { MockAction.MapRemote(port = 0) }.isFailure)
        assertTrue(runCatching { MockAction.MapRemote(port = 70_000) }.isFailure)
        assertTrue(runCatching { MockAction.MapRemote(host = " ") }.isFailure)
        assertTrue(runCatching { MockAction.MapRemote(host = "bad/host") }.isFailure)
        assertTrue(runCatching { MockAction.MapRemote(path = "no-slash") }.isFailure)
        assertEquals("staging.test", MockAction.MapRemote(host = "staging.test").host)

        assertTrue(runCatching { MockAction.Throttle(bytesPerSecond = 0) }.isFailure)
        assertTrue(runCatching { MockAction.Throttle(bytesPerSecond = 104_857_601) }.isFailure)
        assertTrue(runCatching { MockAction.Throttle(bytesPerSecond = 100, latencyMs = -1) }.isFailure)
        assertTrue(runCatching { MockAction.Throttle(bytesPerSecond = 100, latencyMs = 30_001) }.isFailure)
        assertTrue(
            runCatching {
                MockAction.Throttle(bytesPerSecond = 100, next = MockAction.Throttle(bytesPerSecond = 100))
            }.isFailure,
        )

        val engine = MockEngine(emptyList())
        val badHeaderAction = MockAction.StaticResponse(200, "ok", mapOf("X-Bad" to "café"))
        assertTrue(
            runCatching {
                engine.upsert(MockRule("seq-bad", 1, action = MockAction.Sequence(listOf(badHeaderAction))))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                engine.upsert(
                    MockRule(
                        "throttle-bad",
                        1,
                        action = MockAction.Throttle(bytesPerSecond = 1_000, next = badHeaderAction),
                    ),
                )
            }.isFailure,
        )
    }
}
