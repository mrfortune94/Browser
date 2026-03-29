package com.browser.app

import com.browser.app.blocking.AdBlocker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AdBlockerTest {

    @Test
    fun testAdBlockerIsEnabledByDefault() {
        val context = RuntimeEnvironment.getApplication()
        val adBlocker = AdBlocker(context)
        assertTrue(adBlocker.isEnabled())
    }

    @Test
    fun testAdBlockerCanBeDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val adBlocker = AdBlocker(context)
        adBlocker.setEnabled(false)
        assertFalse(adBlocker.isEnabled())
    }

    @Test
    fun testAdBlockerCanBeEnabled() {
        val context = RuntimeEnvironment.getApplication()
        val adBlocker = AdBlocker(context)
        adBlocker.setEnabled(false)
        adBlocker.setEnabled(true)
        assertTrue(adBlocker.isEnabled())
    }

    @Test
    fun testShouldBlockKnownTrackerUrl() {
        val context = RuntimeEnvironment.getApplication()
        val adBlocker = AdBlocker(context)
        adBlocker.setEnabled(false)
        assertFalse(adBlocker.shouldBlock("https://doubleclick.net/ads"))
    }

    @Test
    fun testShouldNotBlockWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val adBlocker = AdBlocker(context)
        adBlocker.setEnabled(false)
        assertFalse(adBlocker.shouldBlock("https://google.com"))
    }

    @Test
    fun testGetBlockedResponseIsNotNull() {
        val context = RuntimeEnvironment.getApplication()
        val adBlocker = AdBlocker(context)
        val response = adBlocker.getBlockedResponse()
        assertNotNull(response)
    }
}
