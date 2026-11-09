package com.barkadakaskas.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppNavigationTest {
    @Test fun transportMapsToNavigationSdkModes() {
        assertEquals(InAppNavigation.Mode.DRIVING, InAppNavigation.mode(null))
        assertEquals(InAppNavigation.Mode.DRIVING, InAppNavigation.mode("taxi"))
        assertEquals(InAppNavigation.Mode.TWO_WHEELER, InAppNavigation.mode("tricycle"))
        assertEquals(InAppNavigation.Mode.CYCLING, InAppNavigation.mode("bicycle"))
        assertEquals(InAppNavigation.Mode.WALKING, InAppNavigation.mode("walking"))
        // Only Google Maps routes these.
        assertNull(InAppNavigation.mode("ferry")); assertNull(InAppNavigation.mode("transit")); assertNull(InAppNavigation.mode("flight"))
    }
}
