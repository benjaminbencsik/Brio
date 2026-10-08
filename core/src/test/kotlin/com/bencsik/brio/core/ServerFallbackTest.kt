package com.bencsik.brio.core

import kotlin.test.*

class ServerFallbackTest {
    @Test fun advancesInOrder() { assertEquals(1, nextUntriedServer(3, 0, setOf(0))) }
    @Test fun wrapsAfterManualChoice() { assertEquals(0, nextUntriedServer(3, 2, setOf(2))) }
    @Test fun skipsPreviouslyFailedServers() { assertEquals(2, nextUntriedServer(3, 0, setOf(0, 1))) }
    @Test fun stopsWhenExhausted() { assertNull(nextUntriedServer(3, 2, setOf(0, 1, 2))) }
    @Test fun singleFailedSourceDoesNotLoop() { assertNull(nextUntriedServer(1, 0, setOf(0))) }
    @Test fun handlesNoSources() { assertNull(nextUntriedServer(0, 0, emptySet())) }
}
