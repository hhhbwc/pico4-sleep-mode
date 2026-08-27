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

        System.out.println("VSleepHookTest passed");
        System.exit(0);
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
