package com.tagforge.simulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Delete once there is something real to test. Proves the engine actually runs. */
class BuildSanityTest {

    @Test
    void virtualThreadsAreAvailable() throws Exception {
        var thread = Thread.ofVirtual().unstarted(() -> { });
        thread.start();
        thread.join();
        assertEquals(21, Runtime.version().feature() >= 21 ? 21 : 0);
    }
}
