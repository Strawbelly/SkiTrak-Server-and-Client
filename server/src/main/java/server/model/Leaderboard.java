package server.model;

import java.util.List;

public class Leaderboard {
    private String resortID;
    private String serverTimestamp;
    private List<Record> top;

    public Leaderboard(String resortID, String serverTimestamp, List<Record> top) {
        this.resortID = resortID;
        this.serverTimestamp = serverTimestamp;
        this.top = top;
    }

    public String getResortID() { return resortID; }
    public String getServerTimestamp() { return serverTimestamp; }
    public List<Record> getTop() { return top; }

    public static class Record {
        private int rank;
        private int skierID;
        private int vertical;

        public Record(int rank, int skierID, int vertical) {
            this.rank = rank;
            this.skierID = skierID;
            this.vertical = vertical;
        }

        public int getRank() { return rank; }
        public int getSkierID() { return skierID; }
        public int getVertical() { return vertical; }
    }
}
