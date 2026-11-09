/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorFlagEditDialogsTest {
    @Test
    fun `boolean flags only accept true or false`() {
        assertTrue(isValidFeatureFlagValue("BOOLEAN", "true"))
        assertTrue(isValidFeatureFlagValue("BOOLEAN", " FALSE "))
        assertFalse(isValidFeatureFlagValue("BOOLEAN", "yes"))
        assertFalse(isValidFeatureFlagValue("BOOLEAN", ""))
    }

    @Test
    fun `numeric flags must parse and use a numeric keyboard`() {
        assertTrue(flagValueUsesNumericKeyboard("INT"))
        assertTrue(flagValueUsesNumericKeyboard("double"))
        assertFalse(flagValueUsesNumericKeyboard("STRING"))
        assertTrue(isValidFeatureFlagValue("INT", "42"))
        assertTrue(isValidFeatureFlagValue("DOUBLE", "-1.5"))
        assertFalse(isValidFeatureFlagValue("INT", "forty-two"))
    }

    @Test
    fun `free-form string flags accept any non-blank text`() {
        assertTrue(isValidFeatureFlagValue("STRING", "staging"))
        assertFalse(isValidFeatureFlagValue("STRING", "   "))
    }
}
