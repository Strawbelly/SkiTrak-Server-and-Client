# AI Usage Reflection

I used Claude Code in the terminal to work directly on the assignment repository.

## Where and for What I Used AI

AI helped implement the initial client and WebSocket server code, including the sender thread pool, swipe generator and producer, WebSocket subscriber, per-swipe records, CSV output, and dummy leaderboard endpoint. I later used Claude Code to audit the implementation against the assignment requirements, restructure the Maven project, add client statistics, verify push-latency measurement, debug connection issues, and refine the documentation.

The initial implementation conversations were not saved. The available transcript covers later auditing and debugging.

## Suggestions I Accepted

- **Adding connection statistics.** Claude Code tested 250 requests against local Tomcat and observed that keep-alive connections were closed after every 100 requests. I accepted its proposal to track connection and reconnection statistics, while treating the results as estimates rather than exact TCP connection counts.
- **Recording one `SwipeRecord` per swipe.** Claude Code reviewed the relevant call sites and helped add per-swipe records to client-part2. Each record captures latency, including retries, and final status. I accepted this because the latency statistics, per-resort throughput, CSV export, and chart depend on per-swipe data. I verified the change with a local run.

## Suggestions I Rejected or Corrected

- **Connection counts presented as exact numbers.** Because workers share an `HttpClient` and may reuse each other's connections, the reported counts could not accurately represent actual TCP connection opens. I required the output to label them as estimates and explain this limitation.
- **The total workload.** Claude Code questioned whether 500,000 swipes included warmup. Based on the instructor's clarification, I used 32,000 warmup swipes plus 500,000 main-phase swipes, for 532,000 total, and updated the documentation and reporting accordingly.

## What the AI Got Wrong

- **Incorrect retry behavior in the README.** AI-generated documentation claimed the client kept retrying while the server was unreachable. I checked `SwipeWorker` and found that each swipe makes one initial attempt plus five retries before being marked as failed. I corrected the README to reflect the actual implementation.

## Decisions I Reasoned Through Myself

- **Little's Law and thread-pool sizing.** Using an average warm-request RTT of 21.71 ms, Little's Law predicted 1,474 swipes/s for 32 workers and 18,422 swipes/s for 400 workers. Measured throughputs were 1,415.9 and 10,590.8 swipes/s, respectively. In a separate comparison, increasing main-phase workers from 400 to 420 improved throughput by only 0.22%, while connection errors increased from 111 to 164. I chose 400 workers because the additional concurrency offered little benefit and more connection errors.
- **Negative push latency.** The median push latency was −47 ms. I reasoned that clock skew between the client and server was a likely explanation, rather than interpreting it as a real negative network delay.

## Transcript

- Claude Code: `transcripts/claude-code-session.txt`