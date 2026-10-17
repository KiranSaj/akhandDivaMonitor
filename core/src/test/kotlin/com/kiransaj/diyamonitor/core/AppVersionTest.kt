package com.kiransaj.diyamonitor.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `newer releases are detected`() {
        assertTrue(AppVersion.isNewer("v1.1.0", "1.0.0"))
        assertTrue(AppVersion.isNewer("v1.10.0", "1.9.3"))
        assertTrue(AppVersion.isNewer("v2", "1.99.99"))
        assertTrue(AppVersion.isNewer("v1.0.1", "1.0"))
    }

    @Test
    fun `same or older releases are not newer`() {
        assertFalse(AppVersion.isNewer("v1.1.0", "1.1.0"))
        assertFalse(AppVersion.isNewer("v1.1", "1.1.0"))
        assertFalse(AppVersion.isNewer("v1.0.9", "1.1.0"))
    }

    @Test
    fun `any release is newer than a dev build`() {
        assertTrue(AppVersion.isNewer("v1.0.0", "0.0.0-dev.42"))
    }

    @Test
    fun `junk tags are never offered`() {
        assertFalse(AppVersion.isNewer("nightly", "1.0.0"))
        assertFalse(AppVersion.isNewer("", "1.0.0"))
    }
}
