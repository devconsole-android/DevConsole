/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Activity
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.BindingMode
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.InitResult
import io.devconsole.api.ScreenshotPolicy
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Exercises [PlatformFacadeProvider.captureScreenshot] end to end: every [ScreenshotResult] branch
 * that does not require an instrumented device (`PixelCopy` against a real device window and the
 * `FLAG_SECURE` rejection have unit coverage of the capture mechanics in [ScreenshotCaptureTest];
 * real-device verification is androidTest-only, see `ScreenshotCaptureInstrumentedTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFacadeProviderScreenshotTest {
    @Test
    fun `screenshot capture is disabled by default and captures nothing`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            val result = provider.captureScreenshot()

            assertEquals(ScreenshotResult.Disabled, result)
        }

    @Test
    fun `no foreground activity reports NoForegroundActivity once capture is enabled`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val config = DevConsoleConfig.default().withScreenshotPolicy(ScreenshotPolicy(enabled = true))
            provider.initialize(ApplicationProvider.getApplicationContext(), config)

            val result = provider.captureScreenshot()

            assertEquals(ScreenshotResult.NoForegroundActivity, result)
        }

    @Test
    fun `a secure foreground window is reported as SecureWindow`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            val config = DevConsoleConfig.default().withScreenshotPolicy(ScreenshotPolicy(enabled = true))
            provider.initialize(application, config)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            activity.window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )

            val result = provider.captureScreenshot()

            assertEquals(ScreenshotResult.SecureWindow, result)
        }

    @Test
    fun `a successful capture is stored unredacted and is readable back over the attachment route`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            val config = DevConsoleConfig.default().withScreenshotPolicy(ScreenshotPolicy(enabled = true))
            assertEquals(InitResult.Initialized, provider.initialize(application, config))
            val started =
                provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8640..8659)) as StartResult.Started
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            activity.window.decorView.measure(
                MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
            )
            activity.window.decorView.layout(0, 0, 100, 100)

            val result = provider.captureScreenshot()

            assertTrue("expected Captured, got $result", result is ScreenshotResult.Captured)
            result as ScreenshotResult.Captured
            assertTrue(result.attachmentId.isNotBlank())
            assertTrue(result.eventId.isNotBlank())
            assertTrue(result.byteCount > 0)

            val accessToken = exchangeSessionCodeOverHttp(started)
            assertTrue(accessToken != null)
            val (status, bytes) = fetchAttachmentOverHttp(started, accessToken!!, result.attachmentId)
            assertEquals(HttpURLConnection.HTTP_OK, status)
            assertTrue("expected non-empty PNG bytes back, got ${bytes.size}", bytes.isNotEmpty())
            // PNG magic number: 0x89 'P' 'N' 'G' ...
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            assertEquals('N'.code.toByte(), bytes[2])
            assertEquals('G'.code.toByte(), bytes[3])

            provider.stop(io.devconsole.api.StopReason.UserRequested)
        }

    private fun exchangeSessionCodeOverHttp(started: StartResult.Started): String? {
        val url = URL("http://${started.endpoint.host}:${started.endpoint.port}/api/v1/auth/session-code/exchange")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            val encodedLabel = URLEncoder.encode("Screenshot Test Browser", "UTF-8")
            val encodedCode = URLEncoder.encode(started.access.sessionCode, "UTF-8")
            connection.outputStream.use { it.write("code=$encodedCode&browserLabel=$encodedLabel".toByteArray()) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().readText()
            Regex("\"accessToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchAttachmentOverHttp(
        started: StartResult.Started,
        accessToken: String,
        attachmentId: String,
    ): Pair<Int, ByteArray> {
        val url = URL("http://${started.endpoint.host}:${started.endpoint.port}/api/v1/attachments/$attachmentId")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            val bytes = if (status == HttpURLConnection.HTTP_OK) connection.inputStream.readBytes() else ByteArray(0)
            status to bytes
        } finally {
            connection.disconnect()
        }
    }
}
