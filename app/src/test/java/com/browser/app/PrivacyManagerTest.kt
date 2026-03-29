package com.browser.app

import com.browser.app.privacy.PrivacyManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyManagerTest {

    @Test
    fun testPrivateModeFalseByDefault() {
        val context = RuntimeEnvironment.getApplication()
        val privacyManager = PrivacyManager(context)
        assertFalse(privacyManager.isPrivateMode)
    }

    @Test
    fun testSpoofedUserAgentContainsMobile() {
        val context = RuntimeEnvironment.getApplication()
        val privacyManager = PrivacyManager(context)
        val ua = privacyManager.getSpoofedUserAgent()
        assertTrue(ua.contains("Mozilla"))
        assertTrue(ua.contains("Chrome"))
    }

    @Test
    fun testSpoofedUserAgentNotEmpty() {
        val context = RuntimeEnvironment.getApplication()
        val privacyManager = PrivacyManager(context)
        val ua = privacyManager.getSpoofedUserAgent()
        assertTrue(ua.isNotEmpty())
    }
}
