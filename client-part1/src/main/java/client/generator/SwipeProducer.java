package client.generator;

import client.modal.Swipe;

import java.util.concurrent.BlockingQueue;

/**
 * Produces swipe events and places them into a blocking queue.
 * Runs in a separate thread so swipe generation can happen concurrently with swipe processing.
 */
public class SwipeProducer implements Runnable {

    private final SwipeGenerator generator;
    private final BlockingQueue<Swipe> queue;
    private final int totalSwipes;

    // Thread that executes this producer.
    private Thread thread;

    /**
     * Creates a producer with the specified generator, queue, and workload size.
     *
     * @param generator  generator used to create swipe events
     * @param queue      blocking queue that receives generated swipes
     * @param totalSwipes total number of swipes to produce
     * @throws IllegalArgumentException if totalSwipes is negative
     */
    public SwipeProducer(SwipeGenerator generator, BlockingQueue<Swipe> queue, int totalSwipes) {
        if (totalSwipes < 0) {
            throw new IllegalArgumentException("totalSwipes must be >= 0");
        }
        this.generator = generator;
        this.queue = queue;
        this.totalSwipes = totalSwipes;
    }

    /**
     * Starts the producer in a dedicated thread.
     *
     * @throws IllegalStateException if the producer has already been started
     */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("Producer already started");
        }
        thread = new Thread(this, "swipe-producer");
        thread.start();
    }

    /**
     * Generates the configured number of swipes and puts them into the queue.
     * If the thread is interrupted while waiting to insert an item, the interrupt
     * status is restored so that the interruption can be handled by the caller.
     */
    @Override
    public void run() {
        try {
            for (int i = 0; i < totalSwipes; i++) {
                queue.put(generator.next());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
