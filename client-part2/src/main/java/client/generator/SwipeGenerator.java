package client.generator;

import client.modal.Swipe;

import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates random ski swipe events for load testing.
 * Each generated swipe contains valid identifiers and a randomly selected swipe time.
 */
public class SwipeGenerator {
    public static final int MAX_SKIER_ID = 100000;
    public static final int MAX_LIFT_ID = 40;
    public static final int MAX_TIME = 360;
    public static final int DEFAULT_RESORTS_NUM = 10;

    private final int numResorts;
    private final int seasonID;
    private final int dayID;

    /**
     * Creates a generator using the default number of resorts,
     * the current year as the season ID, and the current day of the year.
     */
    public SwipeGenerator() {
        this(DEFAULT_RESORTS_NUM, LocalDate.now().getYear(), LocalDate.now().getDayOfYear());
    }

    /**
     * Creates a generator with the specified resort count, season, and day.
     *
     * @param numResorts number of resorts available for generated swipes
     * @param seasonID   season identifier
     * @param dayID      day identifier
     * @throws IllegalArgumentException if any argument is less than 1
     */
    public SwipeGenerator(int numResorts, int seasonID, int dayID) {
        if (numResorts < 1) throw new IllegalArgumentException("numResorts must be >= 1");
        if (seasonID < 1) throw new IllegalArgumentException("seasonID must be >= 1");
        if (dayID < 1) throw new IllegalArgumentException("dayID must be >= 1");
        this.numResorts = numResorts;
        this.seasonID = seasonID;
        this.dayID = dayID;
    }

    /**
     * Generates a swipe with randomly selected skier, resort, lift, and time values.
     * The configured season ID and day ID are used for every generated swipe.
     *
     * @return a newly generated swipe
     */
    public Swipe next() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return new Swipe(
                rnd.nextInt(1, MAX_SKIER_ID + 1),
                rnd.nextInt(1, numResorts + 1),
                rnd.nextInt(1, MAX_LIFT_ID + 1),
                seasonID,
                dayID,
                rnd.nextInt(1, MAX_TIME + 1));
    }

    public int getNumResorts() { return numResorts; }
    public int getSeasonID() { return seasonID; }
    public int getDayID() { return dayID; }
}
