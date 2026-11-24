package com.cadfield.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun padding() = assertEquals("007", 7.toString().padStart(3, '0'))
}
