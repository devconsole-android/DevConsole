package io.devconsole.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentOptionsTest {
    @Test
    fun `parses the documented flags`() {
        val options =
            AgentOptions.parse(
                arrayOf(
                    "--device",
                    "http://localhost:4321",
                    "--code",
                    "123456",
                    "--name",
                    "My Mac",
                    "--allow-host",
                    "a.example,b.example",
                    "--allow-host",
                    "c.example",
                    "--block-private",
                ),
            )

        assertEquals("localhost", options.deviceUrl.host)
        assertEquals("123456", options.code)
        assertEquals("My Mac", options.name)
        assertEquals(setOf("a.example", "b.example", "c.example"), options.allowHosts)
        assertTrue(options.blockPrivate)
    }

    @Test
    fun `device is required and must be an http url`() {
        assertFailsWith<UsageException> { AgentOptions.parse(emptyArray()) }
        assertFailsWith<UsageException> { AgentOptions.parse(arrayOf("--device", "localhost:4321")) }
        assertFailsWith<UsageException> { AgentOptions.parse(arrayOf("--device", "ftp://x")) }
    }

    @Test
    fun `code and token are mutually exclusive and unknown flags fail`() {
        assertFailsWith<UsageException> {
            AgentOptions.parse(arrayOf("--device", "http://localhost:1", "--code", "1", "--token", "t"))
        }
        assertFailsWith<UsageException> { AgentOptions.parse(arrayOf("--device", "http://localhost:1", "--bogus")) }
        assertFailsWith<UsageException> { AgentOptions.parse(arrayOf("--device")) }
    }
}
