package client;


import client.generator.SwipeGenerator;
import client.modal.Swipe;
import client.generator.SwipeProducer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Minimal demo/check of the single producer + bounded queue. Plain main() so no
 * test framework needs to be added to the server's pom.xml.
 *
 * Queue capacity (100) is deliberately smaller than the event count (1,000), so
 * the producer cannot finish unless a consumer drains the queue concurrently.
 */
public class SwipeProducerDemo {
    private static final int TOTAL_EVENTS = 1_000;
    private static final int QUEUE_CAPACITY = 100;

    public static void main(String[] args) throws InterruptedException {
        SwipeGenerator generator = new SwipeGenerator();
        BlockingQueue<Swipe> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        // 1. Exactly one producer, started once
        SwipeProducer producer = new SwipeProducer(generator, queue, TOTAL_EVENTS);
        producer.start();
        Thread producerThread = findProducerThread();
        try {
            producer.start();
            check(false, "second start() should have been rejected");
        } catch (IllegalStateException expected) {
            // ok
        }
        check(countProducerThreads() == 1, "expected exactly 1 swipe-producer thread");

        // 4-6. Consumer (main thread) takes swipes while the producer is still running
        int consumed = 0;
        int consumedWhileProducerAlive = 0;
        int maxQueueSize = 0;
        while (consumed < TOTAL_EVENTS) {
            maxQueueSize = Math.max(maxQueueSize, queue.size());
            Swipe s = queue.take();
            if (producerThread.isAlive()) consumedWhileProducerAlive++;
            checkSwipe(s, generator);
            consumed++;
        }

        // 7. Producer terminates after exactly the configured number of events
        producerThread.join();
        check(!producerThread.isAlive(), "producer thread should have terminated");
        int produced = consumed + queue.size();
        check(produced == TOTAL_EVENTS, "produced " + produced + ", expected " + TOTAL_EVENTS);
        check(queue.isEmpty(), "queue should be empty, has " + queue.size());
        check(maxQueueSize <= QUEUE_CAPACITY, "queue exceeded capacity: " + maxQueueSize);
        check(consumedWhileProducerAlive > 0, "producer and consumer never overlapped");

        System.out.println("  producer threads:            1");
        System.out.println("  produced:                    " + produced);
        System.out.println("  consumed:                    " + consumed);
        System.out.println("  consumed while producing:    " + consumedWhileProducerAlive);
        System.out.println("  max queue size / capacity:   " + maxQueueSize + " / " + QUEUE_CAPACITY);
        System.out.println("SwipeProducerDemo PASSED");
    }

    private static void checkSwipe(Swipe s, SwipeGenerator gen) {
        check(s != null, "null swipe");
        check(s.getSkierID() >= 1 && s.getSkierID() <= 100_000, "skierID out of range: " + s);
        check(s.getResortID() >= 1 && s.getResortID() <= gen.getNumResorts(), "resortID out of range: " + s);
        check(s.getLiftID() >= 1 && s.getLiftID() <= 40, "liftID out of range: " + s);
        check(s.getTime() >= 1 && s.getTime() <= 360, "time out of range: " + s);
        check(s.getSeasonID() == gen.getSeasonID(), "seasonID changed: " + s);
        check(s.getDayID() == gen.getDayID(), "dayID changed: " + s);
    }

    private static Thread findProducerThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("swipe-producer"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("swipe-producer thread not found"));
    }

    private static long countProducerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("swipe-producer"))
                .count();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
