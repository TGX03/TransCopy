package de.tgx03;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Similar to a countdown latch, however this also allows counting up when new tasks emerge.
 */
public class DynamicLatch {

    /**
     * The atomic integer actually used for counting.
     */
    private final AtomicInteger count = new AtomicInteger();
    /**
     * Whether this latch was already broken.
     */
    private volatile boolean broken = false;

    /**
     * Increase the internal count of this latch by one.
     */
    public void countUp() {
        if (broken) throw new IllegalStateException("This latch has already been broken");
        count.incrementAndGet();
    }

    /**
     * Decrease the internal count of this latch by one.
     * Release all waiting threads when it reaches 0.
     */
    public void countDown() {
        if (broken) throw new IllegalStateException("This latch has already been broken");
        int value = count.decrementAndGet();
        if (value == 0) {
            broken = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     * Wait until the latch reaches zero.
     *
     * @throws InterruptedException If the waiting thread gets interrupted.
     */
    public synchronized void await() throws InterruptedException {
        while (!broken) wait();
    }
}
