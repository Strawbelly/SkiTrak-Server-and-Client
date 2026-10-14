package client.worker;

import client.modal.Swipe;
import client.modal.SwipeRecord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class SwipeWorker implements Runnable {

    // Retry settings
    public static final int MAX_RETRIES = 5;
    public static final long BASE_BACKOFF_MS = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final String baseUrl;
    private final BlockingQueue<Swipe> queue;
    private final AtomicInteger remainingSwipes;

    // Per-worker statistics
    private int successCount = 0;
    private int failureCount = 0;
    private int retryCount = 0;
    private int serverClosedCount = 0;
    private int connectionErrorCount = 0;

    // Stores swipes that fail after all allowed retries.
    private final List<Swipe> failedSwipes = new ArrayList<>();

    // Per-swipe timing and result records.
    private final List<SwipeRecord> records = new ArrayList<>();

    public SwipeWorker(HttpClient client, String baseUrl,
                       BlockingQueue<Swipe> queue,
                       AtomicInteger remainingSwipes) {
        this.client = client;

        // Remove a trailing slash to avoid duplicate slashes in request URLs.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        this.queue = queue;
        this.remainingSwipes = remainingSwipes;
    }

    @Override
    public void run() {
        try {
            // Atomically claim swipe tasks until the shared task count is exhausted.
            while (remainingSwipes.getAndDecrement() > 0) {
                // Wait until a swipe is available in the queue.
                Swipe swipe = queue.take();

                // Time the whole swipe: from just before the first attempt until the final
                // response (or the last failed attempt), so latency includes any retries.
                long sendTimeMillis = System.currentTimeMillis();
                long startNanos = System.nanoTime();

                // Send the swipe and retry temporary failures when appropriate.
                int status = send(swipe);

                double latencyMs = (System.nanoTime() - startNanos) / 1e6;
                long responseTimeMillis = System.currentTimeMillis();

                // Save exactly one record per swipe, successful or failed (status 0 = no response).
                SwipeRecord record = new SwipeRecord(sendTimeMillis, responseTimeMillis,
                        latencyMs, status, swipe.getResortID());
                records.add(record);

                if (record.isSuccess()) {
                    successCount++;
                } else {
                    // Record the swipe if it still fails after all attempts.
                    failureCount++;
                    failedSwipes.add(swipe);
                }
            }
        } catch (InterruptedException e) {
            // Restore the interrupt flag so other code can detect the interruption.
            Thread.currentThread().interrupt();
        }
    }

    // Send a swipe and retry temporary failures with exponential backoff.
    private int send(Swipe swipe) throws InterruptedException {
        // Build the HTTP POST request for this swipe.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + swipe.toPath()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(swipe.toJsonBody()))
                .build();

        // Status code 0 means that no HTTP response was received.
        int status = 0;

        // Attempt 0 is the initial request; attempts 1-5 are retries.
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                retryCount++;

                // Exponential backoff: 100, 200, 400, 800, and 1600 ms.
                Thread.sleep(BASE_BACKOFF_MS << (attempt - 1));
            }

            try {
                // Send the request synchronously and discard the response body.
                HttpResponse<Void> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

                status = response.statusCode();

                // Count responses that explicitly request connection closure.
                if (response.headers().firstValue("connection")
                        .orElse("").equalsIgnoreCase("close")) {
                    serverClosedCount++;
                }

                // Any 2xx response indicates success.
                if (status >= 200 && status < 300) {
                    return status;
                }

                // Do not retry most 4xx errors; retry 429 and 5xx responses.
                if (status != 429 && status < 500) {
                    return status;
                }
            } catch (IOException e) {
                // Record I/O errors and allow another attempt if available.
                connectionErrorCount++;
                status = 0;
            }
        }

        // Return the final status after all permitted attempts.
        return status;
    }

    // Return this worker's successful swipe count.
    public int getSuccessCount() {
        return successCount;
    }

    // Return this worker's failed swipe count.
    public int getFailureCount() {
        return failureCount;
    }

    // Return the total number of retry attempts made by this worker.
    public int getRetryCount() {
        return retryCount;
    }

    // Return the number of responses explicitly indicating connection closure.
    public int getServerClosedCount() {
        return serverClosedCount;
    }

    // Return the number of I/O errors encountered while sending requests.
    public int getConnectionErrorCount() {
        return connectionErrorCount;
    }

    // Return the swipes that failed after all allowed attempts.
    public List<Swipe> getFailedSwipes() {
        return failedSwipes;
    }

    // Return this worker's per-swipe records.
    public List<SwipeRecord> getRecords() {
        return records;
    }
}