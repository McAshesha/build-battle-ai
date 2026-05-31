package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutablePlotSceneConcurrencyTest {

    @Test
    void clearAllBlocksWhileReaderHoldsReadLock() throws InterruptedException {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 8, 8, 8, false);
        scene.setBlock(0, 0, 0, XMaterial.STONE, "minecraft:stone");

        CountDownLatch readerAcquired = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicReference<Throwable> readerError = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            Lock l = scene.readLock();
            l.lock();
            try {
                readerAcquired.countDown();
                releaseReader.await(2, TimeUnit.SECONDS);
            } catch (Throwable t) {
                readerError.set(t);
            } finally {
                l.unlock();
            }
        }, "test-reader");
        reader.start();

        assertTrue(readerAcquired.await(1, TimeUnit.SECONDS),
                "reader failed to acquire read-lock");

        // Start clearAll on a second thread — it must block while the reader holds the lock.
        CountDownLatch clearStarted = new CountDownLatch(1);
        CountDownLatch clearDone = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            clearStarted.countDown();
            scene.clearAll();
            clearDone.countDown();
        }, "test-writer");
        writer.start();

        // Give clearAll ample time to start and (correctly) block on the read-lock.
        assertTrue(clearStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse(clearDone.await(50, TimeUnit.MILLISECONDS),
                "clearAll completed while a reader held the lock — read-write exclusivity broken");

        // Pre-clear value is still observable through the reader.
        assertEquals(XMaterial.STONE, scene.getBlockType(0, 0, 0));

        // Release the reader; clearAll must now complete promptly.
        releaseReader.countDown();
        assertTrue(clearDone.await(1, TimeUnit.SECONDS),
                "clearAll did not complete after reader released the lock");

        reader.join(1000);
        writer.join(1000);
        assertEquals(null, readerError.get(), "reader threw");

        // Post-clear: every cell is AIR.
        for (int x = 0; x < 8; x++)
            for (int y = 0; y < 8; y++)
                for (int z = 0; z < 8; z++)
                    assertEquals(XMaterial.AIR, scene.getBlockType(x, y, z));
    }

    @Test
    void concurrentReadsAndWritesAreStable() throws Exception {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 16, 16, 16, false);

        ExecutorService writers = Executors.newSingleThreadExecutor();
        ExecutorService readers = Executors.newFixedThreadPool(4);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(5);

        // Writer: do 5000 set/clear ops on a small set of cells.
        writers.submit(() -> {
            try {
                for (int i = 0; i < 5000; i++) {
                    int x = i & 0xF, y = (i >> 4) & 0xF, z = (i >> 8) & 0xF;
                    if ((i & 1) == 0)
                        scene.setBlock(x, y, z, XMaterial.STONE, "minecraft:stone");
                    else
                        scene.clearBlock(x, y, z);
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        // 4 readers: take readLock, scan, release. Repeat 50 times.
        for (int r = 0; r < 4; r++) {
            readers.submit(() -> {
                try {
                    for (int n = 0; n < 50; n++) {
                        Lock l = scene.readLock();
                        l.lock();
                        try {
                            int air = 0, stone = 0;
                            for (int x = 0; x < 16; x++)
                                for (int y = 0; y < 16; y++)
                                    for (int z = 0; z < 16; z++) {
                                        XMaterial m = scene.getBlockType(x, y, z);
                                        if (m == XMaterial.AIR) air++;
                                        else if (m == XMaterial.STONE) stone++;
                                    }
                            // Every cell is either AIR or STONE — never other.
                            assertEquals(16 * 16 * 16, air + stone,
                                    "observed an unexpected material");
                        } finally {
                            l.unlock();
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(15, TimeUnit.SECONDS), "concurrency test timed out");
        writers.shutdown();
        readers.shutdown();
        assertEquals(null, failure.get(), "concurrent test failed: " + failure.get());
    }
}
