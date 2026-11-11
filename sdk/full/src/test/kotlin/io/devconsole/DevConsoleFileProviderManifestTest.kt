/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DevConsoleFileProviderManifestTest {
    @Test
    fun `provider declares the paths meta-data that getUriForFile reads`() {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/AndroidManifest.xml"))
        val providers = document.getElementsByTagName("provider")
        val provider =
            (0 until providers.length)
                .map { providers.item(it) as Element }
                .single { it.getAttribute("android:name") == "io.devconsole.DevConsoleFileProvider" }
        val metaData = provider.getElementsByTagName("meta-data")

        assertEquals(1, metaData.length)
        val paths = metaData.item(0) as Element
        assertEquals("android.support.FILE_PROVIDER_PATHS", paths.getAttribute("android:name"))
        assertEquals("@xml/devconsole_file_provider_paths", paths.getAttribute("android:resource"))
    }
}
