package client;

import client.generator.SwipeGenerator;
import client.modal.Swipe;
import client.websocket.LeaderboardSubscriber;
import client.generator.SwipeProducer;
import client.worker.SwipeWorker;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Runs the HTTP load test with separate warmup and main phases.
// Also starts WebSocket subscribers and prints ingest and subscription metrics.
//
// Usage: java -cp target/classes client.LoadTestClient [baseUrl] [mainWorkers] [totalSwipes]
public class LoadTestClient {

    // Default server URL used when no URL is provided through command-line arguments.
    private static final String DEFAULT_BASE_URL = "http://35.162.4.183:8080/ski-servlet";

    private static final int TOTAL_SWIPES = 532000;
    private static final int QUEUE_CAPACITY = 10000;

    // Warmup configuration: 32 workers, each responsible for 1,000 swipes.
    private static final int WARMUP_WORKERS = 32;
    private static final int WARMUP_SWIPES_PER_WORKER = 1000;
    private static final int WARMUP_SWIPES = WARMUP_WORKERS * WARMUP_SWIPES_PER_WORKER;

    // Number of workers used during the main load test.
    private static final int MAIN_WORKERS = 400;

    // Creates one subscriber for each configured resort.
    private static final int NUM_SUBSCRIBERS = SwipeGenerator.DEFAULT_RESORTS_NUM;

    public static void main(String[] args) throws InterruptedException {
        // Read optional command-line arguments, falling back to the default configuration.
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        int mainWorkerCount = args.length > 1 ? Integer.parseInt(args[1]) : MAIN_WORKERS;
        int totalSwipes = args.length > 2 ? Integer.parseInt(args[2]) : TOTAL_SWIPES;

        if (mainWorkerCount < 1 || totalSwipes <= WARMUP_SWIPES) {
            throw new IllegalArgumentException("need mainWorkers >= 1 and totalSwipes > " + WARMUP_SWIPES);
        }

        int mainSwipes = totalSwipes - WARMUP_SWIPES;

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        SwipeGenerator generator = new SwipeGenerator();

        System.out.println("Target: " + baseUrl);
        System.out.println("Main workers: " + mainWorkerCount + ", total swipes: " + totalSwipes);

        // Create a separate HTTP client for the WebSocket subscribers.
        // Connect subscribers before starting the ingest test.
        HttpClient subscriberClient = HttpClient.newBuilder().build();
        List<LeaderboardSubscriber> subscribers = new ArrayList<>();

        for (int i = 0; i < NUM_SUBSCRIBERS; i++) {
            subscribers.add(new LeaderboardSubscriber(
                    subscriberClient,
                    baseUrl,
                    (i % generator.getNumResorts()) + 1));
        }

        for (LeaderboardSubscriber s : subscribers) {
            s.start();
        }

        // Wait for subscribers to establish their initial WebSocket connections.
        int connected = 0;
        for (LeaderboardSubscriber s : subscribers) {
            if (s.awaitConnected(10, TimeUnit.SECONDS)) {
                connected++;
            }
        }

        System.out.println("Subscribers connected: " + connected + "/" + NUM_SUBSCRIBERS);

        // Start the overall timer before generating and processing the swipe workload.
        long testStart = System.nanoTime();

        BlockingQueue<Swipe> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        new SwipeProducer(generator, queue, totalSwipes).start();

        // Warmup phase
        List<SwipeWorker> warmupWorkers = new ArrayList<>();
        for (int i = 0; i < WARMUP_WORKERS; i++) {
            warmupWorkers.add(new SwipeWorker(
                    client,
                    baseUrl,
                    queue,
                    new AtomicInteger(WARMUP_SWIPES_PER_WORKER)));
        }

        // Run the warmup workers and measure their elapsed time.
        long warmupNanos = runWorkers("Warmup", warmupWorkers);

        // Main phase
        AtomicInteger mainRemaining = new AtomicInteger(mainSwipes);
        List<SwipeWorker> mainWorkers = new ArrayList<>();

        for (int i = 0; i < mainWorkerCount; i++) {
            mainWorkers.add(new SwipeWorker(client, baseUrl, queue, mainRemaining));
        }

        // Run the main workers and measure their elapsed time.
        long mainNanos = runWorkers("Main", mainWorkers);

        // Calculate overall elapsed time
        long totalNanos = System.nanoTime() - testStart;

        // Stop the WebSocket subscribers after ingestion has finished.
        for (LeaderboardSubscriber s : subscribers) {
            s.stop();
        }

        // Aggregate each worker's final results.
        int warmupSuccess = sumSuccess(warmupWorkers);
        int warmupFailure = sumFailure(warmupWorkers);
        int mainSuccess = sumSuccess(mainWorkers);
        int mainFailure = sumFailure(mainWorkers);
        int totalSuccess = warmupSuccess + mainSuccess;
        int totalFailure = warmupFailure + mainFailure;
        int totalAttempted = totalSuccess + totalFailure;

        double totalSeconds = totalNanos / 1e9;

        // Print per-phase results; each phase has its own workers, counters and timer.
        printPhase("Warmup", warmupWorkers, WARMUP_SWIPES, warmupSuccess, warmupFailure, warmupNanos);
        printPhase("Main", mainWorkers, mainSwipes, mainSuccess, mainFailure, mainNanos);

        // Print overall ingest results
        System.out.println();
        System.out.println("=== Overall Results ===");
        System.out.println("Successful swipes:   " + totalSuccess);
        System.out.println("Failed swipes:       " + totalFailure + "  (after " + SwipeWorker.MAX_RETRIES + " retries)");
        System.out.printf("Total runtime:       %.3f s  (wall clock, warmup + main)%n", totalSeconds);
        System.out.printf("Overall throughput:  %.1f swipes/s  (successful / total runtime)%n",
                totalSuccess / totalSeconds);

        if (totalAttempted != totalSwipes) {
            System.out.println("WARNING: " + totalAttempted + " swipes attempted, expected " + totalSwipes);
        }

        // Print ingest connection-related statistics.
        printConnectionStats(warmupWorkers, mainWorkers);

        // Print WebSocket update counts and connection establishment times.
        printSubscribeResults(subscribers);
    }

    // Prints per-subscriber leaderboard update counts and WebSocket connection times.
    private static void printSubscribeResults(List<LeaderboardSubscriber> subscribers) {
        System.out.println();
        System.out.println("=== Subscribe ===");
        System.out.println("Subscriber  Resort  Updates  FirstConnect(ms)");

        int totalReconnects = 0;
        List<Double> allConnectTimes = new ArrayList<>();

        for (int i = 0; i < subscribers.size(); i++) {
            LeaderboardSubscriber s = subscribers.get(i);
            List<Double> connectTimes = s.getConnectTimesMs();

            totalReconnects += s.getReconnectCount();
            allConnectTimes.addAll(connectTimes);

            System.out.printf("%10d  %6d  %7d  %16s%n",
                    i + 1,
                    s.getResortID(),
                    s.getUpdateCount(),
                    connectTimes.isEmpty()
                            ? "n/a"
                            : String.format("%.1f", connectTimes.get(0)));
        }

        // Summarize every successful connection, including reconnects.
        if (allConnectTimes.isEmpty()) {
            System.out.println("WebSocket connect time: n/a (no subscriber connected)");
        } else {
            double sum = 0;
            for (double t : allConnectTimes) {
                sum += t;
            }

            System.out.printf(
                    "WebSocket connect time: mean %.1f ms, min %.1f ms, max %.1f ms  (%d connections, %d reconnects)%n",
                    sum / allConnectTimes.size(),
                    Collections.min(allConnectTimes),
                    Collections.max(allConnectTimes),
                    allConnectTimes.size(),
                    totalReconnects);
        }
    }

    // Prints HTTP connection statistics for the ingest path.
    private static void printConnectionStats(
            List<SwipeWorker> warmupWorkers,
            List<SwipeWorker> mainWorkers) {

        int closed = 0;
        int errors = 0;

        // Aggregate server-closed responses and I/O errors from both phases.
        for (SwipeWorker w : warmupWorkers) {
            closed += w.getServerClosedCount();
            errors += w.getConnectionErrorCount();
        }
        for (SwipeWorker w : mainWorkers) {
            closed += w.getServerClosedCount();
            errors += w.getConnectionErrorCount();
        }

        // Each worker sends synchronously, so at most one connection per worker is in use.
        int peakWorkers = Math.max(warmupWorkers.size(), mainWorkers.size());
        int reconnections = closed + errors;

        System.out.println();
        System.out.println("=== Connection Statistics (ingest) ===");
        System.out.println("Server-closed responses:  " + closed + "  (measured: \"Connection: close\")");
        System.out.println("Connection errors:        " + errors + "  (measured: IOExceptions)");
        System.out.println("Reconnections:            ~" + reconnections + "  (estimate: server-closed + errors)");
        System.out.println("Connections opened:       ~" + (peakWorkers + reconnections)
                + "  (estimate: up to " + peakWorkers + " concurrent + reconnections)");
        System.out.println("Note: the JDK HttpClient exposes no pool statistics, so actual TCP connections are not counted.");
    }

    // Prints one phase's results using only that phase's workers, counters and runtime.
    private static void printPhase(String phase, List<SwipeWorker> workers, int workload,
                                   int success, int failure, long nanos) {
        double seconds = nanos / 1e9;
        int retries = 0;
        for (SwipeWorker worker : workers) {
            retries += worker.getRetryCount();
        }

        System.out.println();
        System.out.println("=== " + phase + " Results ===");
        System.out.println("Workers:     " + workers.size());
        System.out.println("Workload:    " + workload);
        System.out.println("Attempted:   " + (success + failure));
        System.out.println("Successful:  " + success);
        System.out.println("Failed:      " + failure);
        System.out.println("Retries:     " + retries + "  (extra attempts, not extra swipes)");
        System.out.printf("Runtime:     %.3f s  (workers start -> all workers finish)%n", seconds);
        System.out.printf("Throughput:  %.1f swipes/s  (successful / %s runtime)%n",
                success / seconds, phase.toLowerCase());
    }

    // Returns the total number of successful swipes across all workers.
    private static int sumSuccess(List<SwipeWorker> workers) {
        int sum = 0;
        for (SwipeWorker worker : workers) {
            sum += worker.getSuccessCount();
        }
        return sum;
    }

    // Returns the total number of failed swipes across all workers.
    private static int sumFailure(List<SwipeWorker> workers) {
        int sum = 0;
        for (SwipeWorker worker : workers) {
            sum += worker.getFailureCount();
        }
        return sum;
    }

    // Runs all workers in a fixed-size thread pool and waits for them to finish.
    // Returns the elapsed time in nanoseconds for the phase.
    private static long runWorkers(String phase, List<SwipeWorker> workers)
            throws InterruptedException {

        System.out.println(phase + ": starting " + workers.size() + " workers");

        long start = System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        List<Future<?>> futures = new ArrayList<>();

        for (SwipeWorker worker : workers) {
            futures.add(pool.submit(worker));
        }

        pool.shutdown();

        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw e;
        } catch (ExecutionException e) {
            pool.shutdownNow();
            throw new RuntimeException(phase + " worker failed", e.getCause());
        }

        long elapsed = System.nanoTime() - start;

        return elapsed;
    }
}