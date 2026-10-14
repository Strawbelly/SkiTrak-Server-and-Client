# SkiTrak Server (Dummy)

A minimal WAR for Tomcat 9 with three endpoints. The server validates and acknowledges swipe requests and pushes a synthetic leaderboard. It does not aggregate or store swipe data.

## Endpoints

| Endpoint | Class | Behavior |
|---|---|---|
| `POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}` | `server.SkierServlet` | Validates a swipe and returns `201 Created` with an empty body |
| `GET /health` | `server.HealthServlet` | Returns `200 OK` with `{"status":"UP"}` |
| `WS /leaderboard/{resortID}` | `server.websocket.LeaderboardEndpoint` | Pushes a synthetic top-10 leaderboard approximately every 500 ms |

All paths are relative to the context path `/ski-servlet`.

## Validation Rules

The swipe endpoint accepts a JSON request body:

```json
{
  "time": 217,
  "liftID": 21
}
```

| Check | Failure response |
|---|---|
| Required path parameters are missing | `404` — `{"message":"missing parameters"}` |
| Path format is invalid or an ID is not a positive integer | `400` — `{"message":"invalid url"}` |
| Request body is not valid JSON | `400` — `{"message":"invalid JSON format"}` |
| `time` is outside 1–360 or `liftID` is outside 1–40 | `400` — `{"message":"invalid JSON body"}` |

Missing `time` or `liftID` fields are treated as `0`. A valid swipe returns `201 Created` with an empty body.

## Leaderboard Messages

The server sends a synthetic leaderboard to connected sessions for the requested resort approximately every 500 ms. The leaderboard is random and is not calculated from swipe requests. The `serverTimestamp` field records the server's send time in UTC and can be used by clients to estimate push latency.

Example message:

```json
{
  "resortID": "7",
  "serverTimestamp": "2026-10-12T03:44:57.613Z",
  "top": [
    {"rank": 1, "skierID": 198, "vertical": 48812},
    {"rank": 2, "skierID": 46790, "vertical": 48544}
  ]
}
```

The example shows two entries; the actual leaderboard contains up to 10 entries. Sessions are removed from the subscriber set when they close or encounter an error.

## Project Structure

```text
server/
├── src/
│   └── main/
│       ├── java/
│       │   └── server/
│       │       ├── SkierServlet.java
│       │       ├── HealthServlet.java
│       │       └── websocket/
│       │           └── LeaderboardEndpoint.java
│       └── webapp/
│           └── WEB-INF/
│               └── web.xml
└── target/
    └── ski-servlet.war
```

This tree highlights the main server components. Refer to the repository for the complete source structure.

## Prerequisites

- JDK 11 or later
- Maven 3.6 or later
- Apache Tomcat 9
- Node.js and npm (only needed for the `wscat` WebSocket test)
- An AWS EC2 instance for remote deployment

## Build

From the repository root, build all modules:

```bash
mvn clean package
```

To build only the server, run this from the `server/` directory:

```bash
mvn clean package
```

The WAR is generated at:

```text
server/target/ski-servlet.war
```

The Maven `finalName` setting names the WAR `ski-servlet.war`, so Tomcat deploys it under the `/ski-servlet` context path.

The WAR contains:

- `WEB-INF/classes/server/` — servlet, WebSocket endpoint, and model classes
- `WEB-INF/web.xml` — servlet mappings
- `WEB-INF/lib/gson-2.10.1.jar` — bundled Gson library

The Servlet API 4.0.1 and WebSocket API 1.1 are provided dependencies supplied by Tomcat 9.

To inspect the WAR from the repository root:

```bash
unzip -l server/target/ski-servlet.war
```

## Deploy to Tomcat 9 on AWS EC2

The following instructions use Amazon Linux 2023. Adjust them if your EC2 instance uses a different operating system.

### 1. Configure the EC2 instance

- Region: `us-west-2` (or your chosen region)
- Instance type: choose an available instance type suitable for your workload
- Security group: allow inbound TCP port `8080` from your testing IP and TCP port `22` from your SSH client IP
- Key pair: your downloaded `.pem` file

### 2. Install Java and Tomcat

Connect to the instance:

```bash
ssh -i <your-key>.pem ec2-user@<EC2_PUBLIC_IP>
```

Install Java 11:

```bash
sudo dnf install -y java-11-amazon-corretto-headless
java -version
```

Download a Tomcat 9.0.x release from the [Apache Tomcat 9 download page](https://tomcat.apache.org/download-90.cgi). Replace `<TOMCAT_VERSION>` with the selected version:

```bash
TOMCAT_VERSION=<TOMCAT_VERSION>
wget https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz
tar xzf apache-tomcat-${TOMCAT_VERSION}.tar.gz
export CATALINA_HOME=~/apache-tomcat-${TOMCAT_VERSION}
```

### 3. Copy and deploy the WAR

From your local machine, run this command from the repository root:

```bash
scp -i <your-key>.pem server/target/ski-servlet.war ec2-user@<EC2_PUBLIC_IP>:~
```

On the EC2 instance:

```bash
cp ~/ski-servlet.war "$CATALINA_HOME/webapps/"
"$CATALINA_HOME/bin/startup.sh"
tail -f "$CATALINA_HOME/logs/catalina.out"
```

Check the logs for successful deployment. Tomcat should unpack the WAR into `webapps/ski-servlet/`.

To stop Tomcat:

```bash
"$CATALINA_HOME/bin/shutdown.sh"
```

For a new build, copy the updated WAR into `webapps/`. If automatic redeployment does not occur, stop Tomcat, remove the exploded `webapps/ski-servlet/` directory, copy the WAR again, and restart Tomcat.

## Testing

Set the base URL to your EC2 deployment:

```bash
BASE=http://<EC2_PUBLIC_IP>:8080/ski-servlet
```

For a local deployment, use:

```bash
BASE=http://localhost:8080/ski-servlet
```

### Health Endpoint

```bash
curl -i "$BASE/health"
```

Expected result: `200 OK` with `{"status":"UP"}`.

### Swipe Endpoint

Valid swipe — expected `201 Created` with an empty body:

```bash
curl -i -X POST \
  -H "Content-Type: application/json" \
  -d '{"time":217,"liftID":21}' \
  "$BASE/skiers/7/seasons/2026/days/1/skiers/40213"
```

Invalid `liftID` — expected `400` with `{"message":"invalid JSON body"}`:

```bash
curl -i -X POST \
  -H "Content-Type: application/json" \
  -d '{"time":217,"liftID":41}' \
  "$BASE/skiers/7/seasons/2026/days/1/skiers/40213"
```

Invalid `resortID` — expected `400` with `{"message":"invalid url"}`:

```bash
curl -i -X POST \
  -H "Content-Type: application/json" \
  -d '{"time":217,"liftID":21}' \
  "$BASE/skiers/abc/seasons/2026/days/1/skiers/40213"
```

These requests can also be sent through Postman using a raw JSON body.

### WebSocket Leaderboard

Install or run `wscat` using Node.js and npm:

```bash
npx wscat -c ws://<EC2_PUBLIC_IP>:8080/ski-servlet/leaderboard/7
```

The server should push leaderboard JSON messages approximately every 500 ms. Press `Ctrl+C` to disconnect.

For a local deployment:

```bash
npx wscat -c ws://localhost:8080/ski-servlet/leaderboard/7
```

## Known Limitations

- Swipe requests are validated and acknowledged but are not aggregated or stored as skier activity.
- The leaderboard is synthetic and is not driven by swipe requests.
- HTTP connection reuse and reconnect behavior depend on the Tomcat connector configuration. Document observed connection behavior alongside the test configuration and results.