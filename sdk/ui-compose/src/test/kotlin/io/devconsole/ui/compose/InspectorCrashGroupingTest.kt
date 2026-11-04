/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorCrashGroupingTest {
    @Test
    fun `same crash at different line numbers produces the same fingerprint`() {
        val first =
            InspectorCrashUi(
                id = "crash-1",
                kind = "UNCAUGHT",
                summary = "IllegalStateException: Order 101 missing",
                thread = "main",
                timestampEpochMs = 1_000L,
                stackTrace =
                    """
                    java.lang.IllegalStateException: Order 101 missing
                        at java.util.Objects.requireNonNull(Objects.java:208)
                        at kotlin.PreconditionsKt.checkNotNull(Preconditions.kt:32)
                        at com.acmeship.checkout.OrderRepository.getOrder(OrderRepository.kt:42)
                        at com.acmeship.checkout.CheckoutViewModel.load(CheckoutViewModel.kt:88)
                    """.trimIndent(),
            )
        val secondMovedLine =
            InspectorCrashUi(
                id = "crash-2",
                kind = "UNCAUGHT",
                summary = "IllegalStateException: Order 202 missing",
                thread = "main",
                timestampEpochMs = 2_000L,
                stackTrace =
                    """
                    java.lang.IllegalStateException: Order 202 missing
                        at java.util.Objects.requireNonNull(Objects.java:208)
                        at kotlin.PreconditionsKt.checkNotNull(Preconditions.kt:32)
                        at com.acmeship.checkout.OrderRepository.getOrder(OrderRepository.kt:195)
                        at com.acmeship.checkout.CheckoutViewModel.load(CheckoutViewModel.kt:120)
                    """.trimIndent(),
            )

        val fp1 = crashFingerprint(first)
        val fp2 = crashFingerprint(secondMovedLine)

        assertEquals(fp1, fp2)
        assertTrue(fp1.contains("java.lang.IllegalStateException"))
        assertTrue(fp1.contains("com.acmeship.checkout.OrderRepository.getOrder"))
        assertFalse(fp1.contains("42"))
        assertFalse(fp1.contains("195"))
    }

    @Test
    fun `framework-only frames fall back to the first stack frame`() {
        val crash =
            InspectorCrashUi(
                id = "crash-framework-only",
                kind = "ANR",
                summary = "Main thread unresponsive",
                thread = "main",
                timestampEpochMs = 3_000L,
                stackTrace =
                    """
                    "main"
                        at android.os.MessageQueue.nativePollOnce(Native Method)
                        at android.os.MessageQueue.next(MessageQueue.java:335)
                        at android.os.Looper.loop(Looper.java:183)
                        at com.android.internal.os.RuntimeInit.main(RuntimeInit.java:392)
                    """.trimIndent(),
            )

        val fingerprint = crashFingerprint(crash)

        assertTrue(fingerprint.contains("Main thread unresponsive"))
        assertTrue(fingerprint.contains("android.os.MessageQueue.nativePollOnce"))
    }

    @Test
    fun `different app methods with same exception class produce distinct fingerprints`() {
        val crashA =
            InspectorCrashUi(
                id = "a",
                kind = "UNCAUGHT",
                summary = "NullPointerException: boom",
                thread = "main",
                timestampEpochMs = 1_000L,
                stackTrace =
                    """
                    java.lang.NullPointerException: boom
                        at com.acmeship.CartManager.add(CartManager.kt:10)
                    """.trimIndent(),
            )
        val crashB =
            InspectorCrashUi(
                id = "b",
                kind = "UNCAUGHT",
                summary = "NullPointerException: boom",
                thread = "main",
                timestampEpochMs = 2_000L,
                stackTrace =
                    """
                    java.lang.NullPointerException: boom
                        at com.acmeship.CartManager.remove(CartManager.kt:20)
                    """.trimIndent(),
            )

        assertNotEquals(crashFingerprint(crashA), crashFingerprint(crashB))
    }

    @Test
    fun `groups crashes by fingerprint with newest group first and newest crash per group`() {
        val npeOld =
            InspectorCrashUi(
                id = "npe-1",
                kind = "UNCAUGHT",
                summary = "NullPointerException: first",
                thread = "main",
                timestampEpochMs = 1_000L,
                stackTrace =
                    "java.lang.NullPointerException: first\n" +
                        "\tat com.acmeship.Cart.checkout(Cart.kt:10)",
            )
        val iseMiddle =
            InspectorCrashUi(
                id = "ise-1",
                kind = "UNCAUGHT",
                summary = "IllegalStateException: middle",
                thread = "worker",
                timestampEpochMs = 3_000L,
                stackTrace =
                    "java.lang.IllegalStateException: middle\n" +
                        "\tat com.acmeship.SyncWorker.run(SyncWorker.kt:50)",
            )
        val npeNewest =
            InspectorCrashUi(
                id = "npe-2",
                kind = "UNCAUGHT",
                summary = "NullPointerException: latest",
                thread = "main",
                timestampEpochMs = 5_000L,
                stackTrace =
                    "java.lang.NullPointerException: latest\n" +
                        "\tat com.acmeship.Cart.checkout(Cart.kt:77)",
            )
        val npeMiddle =
            InspectorCrashUi(
                id = "npe-3",
                kind = "UNCAUGHT",
                summary = "NullPointerException: mid",
                thread = "main",
                timestampEpochMs = 2_000L,
                stackTrace =
                    "java.lang.NullPointerException: mid\n" +
                        "\tat com.acmeship.Cart.checkout(Cart.kt:44)",
            )

        val crashes = listOf(npeOld, iseMiddle, npeNewest, npeMiddle)
        assertTrue(hasDuplicateCrashFingerprints(crashes))
        assertFalse(hasDuplicateCrashFingerprints(listOf(npeOld, iseMiddle)))

        val groups = groupCrashes(crashes)

        assertEquals(2, groups.size)
        // Newest group first (npe group has lastSeen = 5000, ise group has lastSeen = 3000)
        assertEquals("npe-2", groups[0].newest.id)
        assertEquals("NullPointerException: latest", groups[0].newest.summary)
        assertEquals(3, groups[0].count)
        assertEquals(5_000L, groups[0].lastSeenEpochMs)

        assertEquals("ise-1", groups[1].newest.id)
        assertEquals(1, groups[1].count)
        assertEquals(3_000L, groups[1].lastSeenEpochMs)
    }
}
