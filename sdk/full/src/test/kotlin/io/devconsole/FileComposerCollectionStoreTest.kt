/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.composer.ComposerBinaryBody
import io.devconsole.composer.ComposerBodyType
import io.devconsole.composer.ComposerCollectionStore
import io.devconsole.composer.ComposerMultipartPart
import io.devconsole.composer.ComposerQueryParameter
import io.devconsole.composer.ComposerRequest
import io.devconsole.composer.ComposerVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileComposerCollectionStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `save two collections then reload in new store instance returns both with identical fields`() {
        val file = File(tempFolder.newFolder(), "composer-collections.json")
        val store1: ComposerCollectionStore = FileComposerCollectionStore(file)

        val req1 = sampleJsonRequest()
        val req2 = sampleMultipartRequest()

        store1.save("First", req1)
        store1.save("Second", req2)

        assertTrue(file.exists())

        val store2: ComposerCollectionStore = FileComposerCollectionStore(file)
        val collections = store2.collections()
        assertEquals(2, collections.size)

        val first = collections.first { it.name == "First" }
        assertEquals("POST", first.request.method)
        assertEquals("https://api.test/v1/resource", first.request.url)
        assertEquals(mapOf("Authorization" to "Bearer test-token", "X-Custom" to "1"), first.request.headers)
        assertEquals("{\"key\":\"value\"}", first.request.body)
        assertEquals(ComposerBodyType.JSON, first.request.bodyType)
        assertEquals(1, first.request.query.size)
        assertEquals("page", first.request.query[0].name)
        assertEquals("1", first.request.query[0].value)
        assertEquals(10_000, first.request.timeoutMs)
        assertFalse(first.request.followRedirects)
        assertEquals(listOf(ComposerVariable("env", "prod", secret = false)), first.request.variables)

        val second = collections.first { it.name == "Second" }
        assertEquals("PUT", second.request.method)
        assertEquals("https://api.test/v2/upload", second.request.url)
        assertEquals(ComposerBodyType.MULTIPART, second.request.bodyType)
        assertEquals(mapOf("field" to "val"), second.request.formFields)
        assertEquals(1, second.request.multipartParts.size)
        assertEquals("doc.txt", second.request.multipartParts[0].fileName)
        // Redaction never persists raw binary uploads; only the file identity survives.
        assertEquals("blob.bin", second.request.binaryBody?.fileName)
        assertEquals(
            0,
            second.request.binaryBody
                ?.bytes
                ?.size,
        )
    }

    @Test
    fun `remove persists changes across store instances`() {
        val file = File(tempFolder.newFolder(), "composer-collections.json")
        val store1: ComposerCollectionStore = FileComposerCollectionStore(file)

        val col1 = store1.save("Req 1", ComposerRequest(method = "GET", url = "https://api.test/1"))
        val col2 = store1.save("Req 2", ComposerRequest(method = "GET", url = "https://api.test/2"))

        assertTrue(store1.remove(col1.id))

        val store2: ComposerCollectionStore = FileComposerCollectionStore(file)
        val remaining = store2.collections()
        assertEquals(1, remaining.size)
        assertEquals(col2.name, remaining[0].name)
        assertEquals(col2.request.url, remaining[0].request.url)
    }

    @Test
    fun `corrupt file starts empty and creates corrupt backup copy`() {
        val folder = tempFolder.newFolder()
        val file = File(folder, "composer-collections.json")
        file.writeText("{ not-valid-json ", StandardCharsets.UTF_8)

        val store: ComposerCollectionStore = FileComposerCollectionStore(file)
        val collections = store.collections()
        assertTrue(collections.isEmpty())

        val corruptFile = File(folder, "composer-collections.json.corrupt")
        assertTrue(corruptFile.exists())
        assertEquals("{ not-valid-json ", corruptFile.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun `unknown version file is set aside and starts empty`() {
        val folder = tempFolder.newFolder()
        val file = File(folder, "composer-collections.json")
        file.writeText("{\"v\":999,\"collections\":[]}", StandardCharsets.UTF_8)

        val store: ComposerCollectionStore = FileComposerCollectionStore(file)
        val collections = store.collections()
        assertTrue(collections.isEmpty())
        assertTrue(File(folder, "composer-collections.json.corrupt").exists())
    }

    @Test
    fun `collection containing a secret variable value does not have secret in file text`() {
        val file = File(tempFolder.newFolder(), "composer-collections.json")
        val store: ComposerCollectionStore = FileComposerCollectionStore(file)

        val secretValue = "super-secret-password-12345"
        val request =
            ComposerRequest(
                method = "POST",
                url = "https://api.test/login",
                variables =
                    listOf(
                        ComposerVariable(name = "password", value = secretValue, secret = true),
                        ComposerVariable(name = "username", value = "admin", secret = false),
                    ),
            )

        store.save("Login", request)

        val fileText = file.readText(StandardCharsets.UTF_8)
        assertFalse(fileText.contains(secretValue))
        assertTrue(fileText.contains("admin"))
        assertTrue(fileText.contains("password"))

        val store2: ComposerCollectionStore = FileComposerCollectionStore(file)
        val loaded = store2.collections().single()
        val secretVar = loaded.request.variables.first { it.name == "password" }
        assertTrue(secretVar.secret)
        assertEquals("", secretVar.value)
    }

    private fun sampleJsonRequest(): ComposerRequest =
        ComposerRequest(
            method = "POST",
            url = "https://api.test/v1/resource",
            headers = mapOf("Authorization" to "Bearer test-token", "X-Custom" to "1"),
            body = "{\"key\":\"value\"}",
            query = listOf(ComposerQueryParameter("page", "1")),
            bodyType = ComposerBodyType.JSON,
            formFields = emptyMap(),
            multipartParts = emptyList(),
            timeoutMs = 10_000,
            followRedirects = false,
            variables = listOf(ComposerVariable("env", "prod", secret = false)),
        )

    private fun sampleMultipartRequest(): ComposerRequest =
        ComposerRequest(
            method = "PUT",
            url = "https://api.test/v2/upload",
            headers = mapOf("Content-Type" to "multipart/form-data"),
            body = null,
            query = emptyList(),
            bodyType = ComposerBodyType.MULTIPART,
            formFields = mapOf("field" to "val"),
            multipartParts =
                listOf(
                    ComposerMultipartPart(
                        name = "file",
                        value = "content",
                        fileName = "doc.txt",
                        contentType = "text/plain",
                    ),
                ),
            binaryBody =
                ComposerBinaryBody(
                    fileName = "blob.bin",
                    contentType = "application/octet-stream",
                    bytes = byteArrayOf(0x01, 0x02, 0x03),
                ),
            timeoutMs = 20_000,
            followRedirects = true,
            variables = emptyList(),
        )
}
