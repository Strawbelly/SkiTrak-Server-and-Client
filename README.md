# SkiTrak Server and Client

SkiTrak ingests ski-lift swipes over HTTP and pushes live leaderboards over WebSockets.
This repository contains:

- a **dummy server** (one WAR for Tomcat 9) that validates and acknowledges swipes and
  pushes a synthetic top-10 leaderboard every 500 ms
- a **multithreaded load client** in two versions. By default it sends 532,000 swipes
  (32,000 warmup + 500,000 main) while 10 WebSocket subscribers listen to the leaderboards.

## Project structure

```
SkiTrak-Server-and-Client/
├── pom.xml          Root aggregator POM (builds all modules)
├── server/          Tomcat 9 WAR: SkierServlet, HealthServlet, LeaderboardEndpoint
│   └── README.md    Endpoints and EC2 deployment
├── client-part1/    Load client (LoadTestClient)
├── client-part2/    Load client with performance analysis (LoadTestClient, ThroughputChart)
└── results/         Output written by client-part2
```

The clients use only the JDK (`java.net.http`). The server uses the Servlet and WebSocket
APIs provided by Tomcat, plus Gson.

## Architecture

```
 client (part1 / part2)                                   server: Tomcat 9, /ski-servlet

 SwipeProducer ─► ArrayBlockingQueue ─► SwipeWorker pool  ── POST swipe ──►  SkierServlet
 (1 thread)       (10,000)              (shared HttpClient) ◄─ 201 / 4xx ──   /skiers/*

 LeaderboardSubscriber × 10                               ── WS connect ──►  LeaderboardEndpoint
 (one per resort)                                         ◄─ push / 500 ms ─  /leaderboard/{resortID}
```

## Prerequisites

- JDK 11+ and Maven 3.6+
- An AWS EC2 instance with Java 11 and Tomcat 9, with port 8080 open
  (see [server/README.md](server/README.md))
- Optional: Node.js, to run `npx wscat`

## Build

```bash
mvn clean package
```

| Module | Artifact |
|---|---|
| server | `server/target/ski-servlet.war` |
| client-part1 | `client-part1/target/ski-client-part1-1.0-SNAPSHOT.jar` |
| client-part2 | `client-part2/target/ski-client-part2-1.0-SNAPSHOT.jar` |

## Run

**1. Deploy the server.** Copy `ski-servlet.war` into Tomcat's `webapps/` and start Tomcat
(full steps in [server/README.md](server/README.md)). Then check it:

```bash
curl -i http://<EC2_PUBLIC_IP>:8080/ski-servlet/health             # 200 {"status":"UP"}
npx wscat -c ws://<EC2_PUBLIC_IP>:8080/ski-servlet/leaderboard/7   # a push every 500 ms
```

**2. Run the clients** from the repository root:

```bash
java -jar client-part1/target/ski-client-part1-1.0-SNAPSHOT.jar [baseUrl] [mainWorkers] [totalSwipes]
java -jar client-part2/target/ski-client-part2-1.0-SNAPSHOT.jar [baseUrl] [mainWorkers] [totalSwipes]
```

| Argument | Default |
|---|---|
| `baseUrl` | `http://35.162.4.183:8080/ski-servlet` (hard-coded EC2 address; pass your current one) |
| `mainWorkers` | `400` |
| `totalSwipes` | `532000`: 32,000 warmup + 500,000 main. The warmup is always 32,000, so the main phase gets `totalSwipes − 32,000`; the value must be greater than 32,000 |

Example: `java -jar client-part2/target/ski-client-part2-1.0-SNAPSHOT.jar http://<EC2_PUBLIC_IP>:8080/ski-servlet 400 | tee results/part2_run.log`

- Part 2 overwrites `results/per_swipe_metrics.csv` and `results/throughput_over_time.png`
  on each run.
- If the server can't be reached, a run can take a very long time; stop it with Ctrl+C.

## Client behavior

- **Generation:** one producer thread fills a bounded queue (10,000), and the sender
  threads only send.
- **Phases:**
  - warmup: 32 threads × 1,000 swipes
  - main: `mainWorkers` threads sharing the remaining `totalSwipes − 32,000` swipes
    (500,000 by default)
- **Sending:** one shared `HttpClient` with keep-alive. Each sender thread has one request in
  flight at a time.
- **Retries:** up to 5 retries with exponential backoff on 5xx, 429 and I/O errors. A swipe
  counts as failed only after its last attempt.
- **WebSockets:** 10 subscribers (one per resort) stay connected for the whole run and
  reconnect with backoff if they drop.
- **Output:** both clients print Warmup, Main and Overall results. Each phase's throughput
  uses only that phase's successes and runtime. Part 1 adds connection and subscribe
  statistics; Part 2 adds latency, per-resort throughput and push-latency statistics.