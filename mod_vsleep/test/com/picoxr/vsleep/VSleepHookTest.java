package com.picoxr.vsleep;

public final class VSleepHookTest {
    public static void main(String[] args) {
        assertEquals(0, VSleepHook.clampIndex(-1, 5));
        assertEquals(0, VSleepHook.clampIndex(0, 5));
        assertEquals(3, VSleepHook.clampIndex(3, 5));
        assertEquals(5, VSleepHook.clampIndex(9, 5));

        long removedAt = 1_000_000L;
        assertEquals(180_000L, VSleepHook.remainingSleepDelay(removedAt, removedAt));
        assertEquals(120_000L, VSleepHook.remainingSleepDelay(removedAt + 60_000L, removedAt));
        assertEquals(1L, VSleepHook.remainingSleepDelay(removedAt + 179_999L, removedAt));
        assertEquals(0L, VSleepHook.remainingSleepDelay(removedAt + 180_000L, removedAt));
        assertEquals(0L, VSleepHook.remainingSleepDelay(removedAt + 240_000L, removedAt));
        assertEquals(180_000L, VSleepHook.remainingSleepDelay(removedAt - 1L, removedAt));

        assertTrue(CoordinationProtocol.parse("2|token|power|balanced") != null);
        assertTrue(CoordinationProtocol.parse("1|token|power|balanced") == null);
        assertTrue(CoordinationProtocol.parse("2|token|unknown|balanced") == null);
        assertTrue(CoordinationProtocol.parse("2|token|power") == null);
        assertTrue(CoordinationProtocol.tokenMatches("2|token|power|balanced", "token"));
        assertTrue(!CoordinationProtocol.tokenMatches("2|token|power|balanced", "other"));
        assertTrue(CoordinationProtocol.laterRequestWins("2|new|power|balanced", "2|old|power|balanced"));
        assertTrue(!CoordinationProtocol.laterRequestWins("2|old|power|balanced", "2|old|power|balanced"));
        assertTrue(CoordinationProtocol.effectiveUiEnabled("vsleep", "active", true));
        assertTrue(!CoordinationProtocol.effectiveUiEnabled("vsleep", "restoring", true));
        assertTrue(!CoordinationProtocol.effectiveUiEnabled("vsleep", "active", false));

        System.out.println("VSleepHookTest passed");
        System.exit(0);
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("expected true");
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
