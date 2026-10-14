package client.modal;

/**
 * Stores the result of a single swipe HTTP request.
 * Records request timing, the final HTTP status code, and the resort ID.
 */
public final class SwipeRecord {
    private final long sendTimeMillis;
    private final long responseTimeMillis;
    private final double latencyMs;
    private final int statusCode;
    private final int resortID;

    /**
     * Creates a record containing the result and timing information of a swipe request.
     *
     * @param sendTimeMillis     timestamp when the request started
     * @param responseTimeMillis timestamp when the request finished
     * @param latencyMs          total request duration, including retries
     * @param statusCode         final HTTP status code, or 0 if no response was received
     * @param resortID           identifier of the ski resort
     */
    public SwipeRecord(long sendTimeMillis, long responseTimeMillis, double latencyMs,
                       int statusCode, int resortID) {
        this.sendTimeMillis = sendTimeMillis;
        this.responseTimeMillis = responseTimeMillis;
        this.latencyMs = latencyMs;
        this.statusCode = statusCode;
        this.resortID = resortID;
    }

    public long getSendTimeMillis() { return sendTimeMillis; }
    public long getResponseTimeMillis() { return responseTimeMillis; }
    public double getLatencyMs() { return latencyMs; }
    public int getStatusCode() { return statusCode; }
    public int getResortID() { return resortID; }
    public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
}