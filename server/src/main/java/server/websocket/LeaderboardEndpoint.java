package server.websocket;

import com.google.gson.Gson;
import server.model.Leaderboard;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {
    private static final int TOP_N = 10;
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // resortID -> sessions subscribed to that resort
    private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService pusher =
            Executors.newSingleThreadScheduledExecutor();

    private static final Gson gson = new Gson();

    static {
        // Dummy: every 500ms, push a synthetic top-N to each resort's subscribers.
        pusher.scheduleAtFixedRate(LeaderboardEndpoint::pushAll, 500, 500, TimeUnit.MILLISECONDS);
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("resortID") String resortID) {
        subscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @OnClose
    public void onClose(Session session, @PathParam("resortID") String resortID) {
        Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.err.println("WebSocket error on session " + session.getId() + ": " + t);
        for (Set<Session> sessions : subscribers.values()) {
            sessions.remove(session);
        }
    }

    private static void pushAll() {
        for (Map.Entry<String, Set<Session>> e : subscribers.entrySet()) {
            String json = syntheticLeaderboard(e.getKey()); // canned/random top-N + serverTimestamp
            for (Session s : e.getValue()) {
                if (s.isOpen()) s.getAsyncRemote().sendText(json);
            }
        }
    }

    private static String syntheticLeaderboard(String resortID) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Leaderboard.Record> top = new ArrayList<>(TOP_N);
        int vertical = 40000 + rnd.nextInt(10000);
        for (int rank = 1; rank <= TOP_N; rank++) {
            top.add(new Leaderboard.Record(rank, 1 + rnd.nextInt(100000), vertical));
            vertical -= rnd.nextInt(1, 500);
        }
        String serverTimestamp = ISO_MILLIS.format(Instant.now());
        return gson.toJson(new Leaderboard(resortID, serverTimestamp, top));
    }
}
