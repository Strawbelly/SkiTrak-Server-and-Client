package client;

import client.generator.SwipeGenerator;
import client.modal.Swipe;
import client.modal.SwipeRecord;
import client.websocket.LeaderboardSubscriber;
import client.generator.SwipeProducer;
import client.worker.SwipeWorker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int WARMUP_SWIPES = WARMUP_WORKERS * WARMUP_SWIPES_PER_WORKER; // 32,000

    // Number of workers used during the main load test.
    private static final int MAIN_WORKERS = 400;

    // Creates one subscriber for each configured resort.
    private static final int NUM_SUBSCRIBERS = SwipeGenerator.DEFAULT_RESORTS_NUM;

    // Output files
    private static final Path RESULTS_DIR = Paths.get("results");
    private static final String SWIPE_CSV = "per_swipe_metrics.csv";
    private static final String THROUGHPUT_CHART = "throughput_over_time.png";
    private static final int CHART_BUCKET_SECONDS = 10;

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
            subscribers.add(new LeaderboardSubscriber(subscriberClient, baseUrl,
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
        long testStartMillis = System.currentTimeMillis(); // wall-clock origin for the throughput chart

        BlockingQueue<Swipe> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        new SwipeProducer(generator, queue, totalSwipes).start();

        // Warmup phase
        List<SwipeWorker> warmupWorkers = new ArrayList<>();
        for (int i = 0; i < WARMUP_WORKERS; i++) {
            warmupWorkers.add(new SwipeWorker(client, baseUrl, queue,
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

        printPhase("Warmup", warmupWorkers, WARMUP_SWIPES,
                warmupSuccess, warmupFailure, warmupNanos);
        printPhase("Main", mainWorkers, mainSwipes,
                mainSuccess, mainFailure, mainNanos);

        double totalSeconds = totalNanos / 1e9;

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

        // Collect the metrics recorded by all workers
        List<SwipeRecord> records = new ArrayList<>(totalSwipes);
        for (SwipeWorker w : warmupWorkers) records.addAll(w.getRecords());
        for (SwipeWorker w : mainWorkers) records.addAll(w.getRecords());

        printIngestLatency(records);
        printThroughputPerResort(records, generator.getNumResorts(), totalSeconds);

        printSubscribeResults(subscribers);

        // Write the results after the benchmark is finished
        try {
            Files.createDirectories(RESULTS_DIR);
            writeSwipeCsv(records, RESULTS_DIR.resolve(SWIPE_CSV));
            writeThroughputChart(records, testStartMillis, RESULTS_DIR.resolve(THROUGHPUT_CHART));
        } catch (IOException e) {
            System.err.println("Could not write results: " + e);
        }
    }

    private static void printIngestLatency(List<SwipeRecord> records) {
        double[] latencies = new double[records.size()];
        double sum = 0;
        for (int i = 0; i < latencies.length; i++) {
            latencies[i] = records.get(i).getLatencyMs();
            sum += latencies[i];
        }
        Arrays.sort(latencies);

        System.out.println();
        System.out.println("=== Ingest Latency (per swipe, ms) ===");
        System.out.println("Swipes measured: " + latencies.length + "  (each swipe once; time includes any retries)");
        if (latencies.length == 0) return;
        System.out.printf("mean %.2f, median %.2f, p95 %.2f, p99 %.2f, min %.2f, max %.2f%n",
                sum / latencies.length,
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                latencies[0], latencies[latencies.length - 1]);
    }

    private static void printThroughputPerResort(List<SwipeRecord> records, int numResorts, double totalSeconds) {
        int[] successByResort = new int[numResorts + 1];
        for (SwipeRecord r : records) {
            if (r.isSuccess()) successByResort[r.getResortID()]++;
        }
        System.out.println();
        System.out.println("=== Throughput per Resort (successful swipes / total runtime) ===");
        System.out.println("Resort  Successful  Throughput(swipes/s)");
        for (int resort = 1; resort <= numResorts; resort++) {
            System.out.printf("%6d  %10d  %20.1f%n",
                    resort, successByResort[resort], successByResort[resort] / totalSeconds);
        }
    }

    // CSV columns: timestamp, requestType, latency, statusCode, resortID
    private static void writeSwipeCsv(List<SwipeRecord> records, Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("timestamp,requestType,latency,statusCode,resortID");
            out.newLine();
            for (SwipeRecord r : records) {
                // timestamp = epoch millis before send; latency in ms
                out.write(r.getSendTimeMillis() + ",SWIPE," + String.format("%.3f", r.getLatencyMs())
                        + "," + r.getStatusCode() + "," + r.getResortID());
                out.newLine();
            }
        }
        System.out.println();
        System.out.println("Per-swipe CSV written: " + file.toAbsolutePath() + " (" + records.size() + " rows)");
    }

    // Count successful swipes in each 10-second bucket
    private static void writeThroughputChart(List<SwipeRecord> records, long testStartMillis, Path file)
            throws IOException {
        long lastResponse = testStartMillis;
        for (SwipeRecord r : records) lastResponse = Math.max(lastResponse, r.getResponseTimeMillis());
        long runMillis = lastResponse - testStartMillis;
        long bucketMillis = CHART_BUCKET_SECONDS * 1000L;
        int numBuckets = (int) Math.max(1, (runMillis + bucketMillis - 1) / bucketMillis);

        int[] counts = new int[numBuckets];
        for (SwipeRecord r : records) {
            if (r.isSuccess()) {
                int bucket = (int) ((r.getResponseTimeMillis() - testStartMillis) / bucketMillis);
                counts[Math.min(bucket, numBuckets - 1)]++;
            }
        }

        double[] bucketStart = new double[numBuckets];
        double[] swipesPerSecond = new double[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            bucketStart[i] = i * CHART_BUCKET_SECONDS;
            // the last bucket is usually partial: divide by its actual length
            long lengthMillis = Math.min(bucketMillis, runMillis - i * bucketMillis);
            swipesPerSecond[i] = lengthMillis > 0 ? counts[i] / (lengthMillis / 1000.0) : 0;
        }

        ThroughputChart.write(bucketStart, swipesPerSecond, CHART_BUCKET_SECONDS, file.toFile());
        System.out.println("Throughput chart written: " + file.toAbsolutePath()
                + " (" + numBuckets + " buckets of " + CHART_BUCKET_SECONDS + " s)");
    }

    // Get a percentile from the sorted latency values
    private static double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static void printSubscribeResults(List<LeaderboardSubscriber> subscribers) {
        System.out.println();
        System.out.println("=== Subscribe Results ===");
        System.out.println("Subscriber  Resort  Updates  Connect(ms)  Drops  Reconnects");

        int totalUpdates = 0;
        List<Double> allConnectTimes = new ArrayList<>();
        List<Long> allLatencies = new ArrayList<>();

        for (int i = 0; i < subscribers.size(); i++) {
            LeaderboardSubscriber s = subscribers.get(i);
            List<Double> connectTimes = s.getConnectTimesMs();

            totalUpdates += s.getUpdateCount();
            allConnectTimes.addAll(connectTimes);
            allLatencies.addAll(s.getPushLatenciesMs());

            System.out.printf("%10d  %6d  %7d  %11s  %5d  %10d%n",
                    i + 1,
                    s.getResortID(),
                    s.getUpdateCount(),
                    connectTimes.isEmpty()
                            ? "n/a"
                            : String.format("%.1f", connectTimes.get(0)),
                    s.getDropCount(),
                    s.getReconnectCount());
        }

        System.out.println();
        System.out.println("Total leaderboard updates delivered: " + totalUpdates);

        if (!allConnectTimes.isEmpty()) {
            double sum = 0;
            for (double t : allConnectTimes) {
                sum += t;
            }

            System.out.printf(
                    "Connection establishment time: mean %.1f ms, min %.1f ms, max %.1f ms  (%d connections incl. reconnects)%n",
                    sum / allConnectTimes.size(),
                    Collections.min(allConnectTimes),
                    Collections.max(allConnectTimes),
                    allConnectTimes.size());
        }

        if (!allLatencies.isEmpty()) {
            Collections.sort(allLatencies);

            double sum = 0;
            for (long l : allLatencies) {
                sum += l;
            }

            System.out.println();
            System.out.println("=== Push latency (receiveTime - serverTimestamp) ===");
            System.out.println("Clock caveat: serverTimestamp and the client clock come from different machines,");
            System.out.println("so this measurement includes clock skew. NTP-synced machines keep it small.");

            System.out.printf(
                    "mean %.1f ms, median %d ms, p95 %d ms, p99 %d ms%n",
                    sum / allLatencies.size(),
                    percentile(allLatencies, 50),
                    percentile(allLatencies, 95),
                    percentile(allLatencies, 99));
        }
    }

    private static long percentile(List<Long> sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static void printPhase(String phase, List<SwipeWorker> workers, int workload, int success, int failure, long nanos) {
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

        System.out.println(phase + ": finished");
        return elapsed;
    }
}