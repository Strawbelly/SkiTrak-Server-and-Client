package client.modal;

/**
 * Represents a single skier swipe submitted to the ski resort server.
 * Stores the swipe information required to construct the HTTP request path
 * and JSON request body.
 */
public final class Swipe {
    private final int skierID;
    private final int resortID;
    private final int liftID;
    private final int seasonID;
    private final int dayID;
    private final int time;

    /**
     * Creates a swipe with the specified skier, resort, lift, season, day, and time.
     *
     * @param skierID  the skier's identifier
     * @param resortID the ski resort's identifier
     * @param liftID   the ski lift's identifier
     * @param seasonID the season's identifier
     * @param dayID    the day of the season
     * @param time     the time of the swipe
     */
    public Swipe(int skierID, int resortID, int liftID, int seasonID, int dayID, int time) {
        this.skierID = skierID;
        this.resortID = resortID;
        this.liftID = liftID;
        this.seasonID = seasonID;
        this.dayID = dayID;
        this.time = time;
    }

    public int getSkierID() { return skierID; }
    public int getResortID() { return resortID; }
    public int getLiftID() { return liftID; }
    public int getSeasonID() { return seasonID; }
    public int getDayID() { return dayID; }
    public int getTime() { return time; }

    public String toPath() {
        return "/skiers/" + resortID + "/seasons/" + seasonID + "/days/" + dayID + "/skiers/" + skierID;
    }

    public String toJsonBody() {
        return "{\"time\":" + time + ",\"liftID\":" + liftID + "}";
    }

    @Override
    public String toString() {
        return "Swipe: {skierID=" + skierID + ", resortID=" + resortID + ", liftID=" + liftID
                + ", seasonID=" + seasonID + ", dayID=" + dayID + ", time=" + time + "}";
    }
}
