package client.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains one persistent WebSocket connection for a resort.
 * If the connection drops during the benchmark, the subscriber reconnects.
 * Failed reconnect attempts use exponential backoff until the connection is restored or the benchmark ends.
 */
public class LeaderboardSubscriber {

    // Maximum time allowed to establish a WebSocket connection.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // Initial delay before retrying a failed connection.
    private static final long BASE_RECONNECT_BACKOFF_MS = 100;

    // Maximum delay between reconnect attempts.
    private static final long MAX_RECONNECT_BACKOFF_MS = 5000;

    private static final String TIMESTAMP_FIELD = "\"serverTimestamp\":\"";

    private final HttpClient client;
    private final URI uri;
    private final int resortID;

    // Indicates whether the subscriber should continue running.
    private volatile boolean running = true;

    // The current WebSocket connection, if one has been established.
    private volatile WebSocket webSocket;

    // Signals when the first WebSocket connection has been established.
    private final CountDownLatch firstConnect = new CountDownLatch(1);

    // Delay used by the exponential reconnect backoff.
    private long reconnectBackoffMs = BASE_RECONNECT_BACKOFF_MS;

    private final List<Double> connectTimesMs = new ArrayList<>();
    private final List<long[]> messages = new ArrayList<>();

    // Number of WebSocket connections that ended unexpectedly while running.
    private int dropCount = 0;

    /**
     * Creates a subscriber for the specified resort.
     *
     * @param client   HTTP client used to establish WebSocket connections
     * @param baseUrl  base URL of the ski resort server
     * @param resortID identifier of the resort to subscribe to
     */
    public LeaderboardSubscriber(HttpClient client, String baseUrl, int resortID) {
        String base = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        this.client = client;

        // Convert the HTTP(S) base URL to a WebSocket URL and append the resort endpoint.
        this.uri = URI.create(
                base.replaceFirst("^http", "ws") + "/leaderboard/" + resortID
        );

        this.resortID = resortID;
    }

    /**
     * Starts establishing the WebSocket connection.
     */
    public void start() {
        connect();
    }

    /**
     * Waits until the first connection attempt succeeds or the timeout expires.
     *
     * @param timeout maximum time to wait
     * @param unit    unit of the timeout value
     * @return true if the first connection was established; false if the timeout expires
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean awaitConnected(long timeout, TimeUnit unit)
            throws InterruptedException {
        return firstConnect.await(timeout, unit);
    }

    /**
     * Stops the subscriber and attempts to close its WebSocket normally.
     * Aborts the connection if a normal close cannot complete in time.
     */
    public void stop() {
        running = false;

        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "run complete")
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                ws.abort();
            }
        }
    }

    /**
     * Starts an asynchronous WebSocket connection attempt.
     * Records the connection establishment time when the attempt succeeds.
     */
    private void connect() {
        if (!running) {
            return;
        }

        long start = System.nanoTime();

        client.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(uri, new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        // Retry after a delay if the connection attempt fails.
                        reconnectAfterBackoff();
                        return;
                    }

                    synchronized (this) {
                        connectTimesMs.add(
                                (System.nanoTime() - start) / 1e6
                        );

                        reconnectBackoffMs = BASE_RECONNECT_BACKOFF_MS;
                    }

                    webSocket = ws;

                    // Release threads waiting for the initial connection.
                    firstConnect.countDown();

                    // The benchmark may have ended while the connection was being established.
                    if (!running) {
                        ws.abort();
                    }
                });
    }

    /**
     * Schedules another connection attempt after an exponentially increasing delay.
     * The delay doubles after each failed attempt, up to the configured maximum.
     */
    private void reconnectAfterBackoff() {
        if (!running) {
            return;
        }

        long delay;

        synchronized (this) {
            delay = reconnectBackoffMs;
            reconnectBackoffMs = Math.min(
                    reconnectBackoffMs * 2,
                    MAX_RECONNECT_BACKOFF_MS
            );
        }

        // Schedule the next attempt without blocking the current callback thread.
        CompletableFuture.runAsync(
                this::connect,
                CompletableFuture.delayedExecutor(
                        delay, TimeUnit.MILLISECONDS
                )
        );
    }

    /**
     * Extracts the server timestamp from a received JSON message and records it alongside the client's receive timestamp.
     *
     * @param json         complete JSON message
     * @param receiveMillis client-side message receive time in milliseconds
     */
    private void recordMessage(String json, long receiveMillis) {
        int i = json.indexOf(TIMESTAMP_FIELD);
        if (i < 0) {
            return;
        }

        int startIdx = i + TIMESTAMP_FIELD.length();
        int endIdx = json.indexOf('"', startIdx);

        try {
            // Parse the server timestamp into epoch milliseconds.
            long serverMillis = Instant.parse(
                    json.substring(startIdx, endIdx)
            ).toEpochMilli();

            synchronized (this) {
                // Store receive time and server time for later latency calculation.
                messages.add(new long[]{receiveMillis, serverMillis});
            }
        } catch (RuntimeException e) {
            // Ignore malformed or unparsable server timestamps.
        }
    }

    /**
     * Handles incoming WebSocket messages and connection lifecycle events.
     */
    private class Listener implements WebSocket.Listener {

        // Accumulates message fragments until a complete text message arrives.
        private final StringBuilder partial = new StringBuilder();

        // Ensures a connection ending is handled at most once.
        private final AtomicBoolean ended = new AtomicBoolean(false);

        /**
         * Processes an incoming text message, which may arrive in fragments.
         */
        @Override
        public CompletionStage<?> onText(
                WebSocket ws, CharSequence data, boolean last) {

            long receiveMillis = System.currentTimeMillis();
            partial.append(data);

            if (last) {
                // Record the complete message and prepare for the next one.
                recordMessage(partial.toString(), receiveMillis);
                partial.setLength(0);
            }

            // Request the next message so the WebSocket can continue receiving data.
            ws.request(1);
            return null;
        }

        /**
         * Handles a WebSocket connection closing.
         */
        @Override
        public CompletionStage<?> onClose(
                WebSocket ws, int statusCode, String reason) {
            connectionEnded();
            return null;
        }

        /**
         * Handles a WebSocket error.
         */
        @Override
        public void onError(WebSocket ws, Throwable error) {
            connectionEnded();
        }

        /**
         * Records an unexpected connection ending and initiates reconnection.
         */
        private void connectionEnded() {
            if (ended.compareAndSet(false, true) && running) {
                synchronized (LeaderboardSubscriber.this) {
                    dropCount++;
                }

                // Reconnect immediately; failed attempts use exponential backoff.
                connect();
            }
        }
    }

    /**
     * Returns the resort associated with this subscriber.
     */
    public int getResortID() {
        return resortID;
    }

    /**
     * Returns the number of valid timestamped leaderboard messages received.
     */
    public synchronized int getUpdateCount() {
        return messages.size();
    }

    /**
     * Returns the number of connection endings recorded while running.
     */
    public synchronized int getDropCount() {
        return dropCount;
    }

    /**
     * Returns the number of successful reconnects, excluding the initial connection.
     */
    public synchronized int getReconnectCount() {
        return Math.max(0, connectTimesMs.size() - 1);
    }

    /**
     * Returns a snapshot of the recorded WebSocket connection establishment times.
     *
     * @return connection times in milliseconds
     */
    public synchronized List<Double> getConnectTimesMs() {
        return new ArrayList<>(connectTimesMs);
    }

    /**
     * Calculates push latency by subtracting the server timestamp from the
     * client receive timestamp for each recorded message.
     *
     * @return push latencies in milliseconds
     */
    public synchronized List<Long> getPushLatenciesMs() {
        List<Long> latencies = new ArrayList<>(messages.size());

        for (long[] message : messages) {
            latencies.add(message[0] - message[1]);
        }

        return latencies;
    }
}