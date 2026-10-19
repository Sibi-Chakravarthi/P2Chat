package com.p2chat.ui

import com.p2chat.ui.nav.NavigationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test
    fun testNavigationDestinationsAreConfiguredCorrectly() {
        val destinations = NavigationDestination.entries
        assertEquals(4, destinations.size)

        val routes = destinations.map { it.route }.toSet()
        assertEquals(4, routes.size)

        for (dest in destinations) {
            assertFalse(dest.route.isBlank())
            assertFalse(dest.title.isBlank())
            assertFalse(dest.contentDescription.isBlank())
            assertTrue(dest.contentDescription.startsWith("Navigate to"))
        }
    }
}
