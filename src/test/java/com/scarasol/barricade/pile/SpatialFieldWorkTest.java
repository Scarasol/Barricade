package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SpatialFieldWorkTest {
    @Test void joinedParallelPhasesPublishEveryIndexedResult() {
        int[] values = new int[4096];
        SpatialField.work(values.length, 1, i -> values[i] = i + 1);
        for (int i = 0; i < values.length; i++) assertEquals(i + 1, values[i]);
        SpatialField.work(7, 256, i -> values[i] = -i);
        for (int i = 0; i < 7; i++) assertEquals(-i, values[i]);
    }
    @Test void failedParallelPhaseWaitsForEveryReaderBeforeReturning() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean finishedReading = new AtomicBoolean();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try {
            Future<?> phase = owner.submit(() -> SpatialField.work(256, 1, i -> {
                try {
                    if (i == 128) {
                        reading.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Reader was not released");
                        finishedReading.set(true);
                    } else if (i == 0) {
                        throw new IllegalStateException("Expected preparation failure");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }));
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> phase.get(100, TimeUnit.MILLISECONDS));
            assertFalse(finishedReading.get());
            release.countDown();
            assertThrows(ExecutionException.class, () -> phase.get(10, TimeUnit.SECONDS));
            assertTrue(finishedReading.get());
        } finally {
            release.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
