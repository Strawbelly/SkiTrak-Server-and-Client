package client;


import client.generator.SwipeGenerator;
import client.modal.Swipe;

/**
 * Minimal check of swipe generation ranges. Plain main() so no test framework
 * needs to be added to the server's pom.xml.
 */
public class SwipeGeneratorTest {
    private static final int SAMPLES = 1_000_000;

    public static void main(String[] args) {
        checkGenerator(new SwipeGenerator());           // defaults: 10 resorts
        checkGenerator(new SwipeGenerator(3, 2026, 276)); // custom resort range
        checkGenerator(new SwipeGenerator(1, 2026, 1));   // single resort

        Swipe e = new Swipe(40213, 7, 21, 2026, 276, 217);
        check(e.toPath().equals("/skiers/7/seasons/2026/days/276/skiers/40213"), "toPath: " + e.toPath());
        check(e.toJsonBody().equals("{\"time\":217,\"liftID\":21}"), "toJsonBody: " + e.toJsonBody());

        System.out.println("SwipeGeneratorTest PASSED");
    }

    private static void checkGenerator(SwipeGenerator gen) {
        int maxResortSeen = 0;
        for (int i = 0; i < SAMPLES; i++) {
            Swipe e = gen.next();
            check(e.getSkierID() >= 1 && e.getSkierID() <= 100_000, "skierID out of range: " + e);
            check(e.getResortID() >= 1 && e.getResortID() <= gen.getNumResorts(), "resortID out of range: " + e);
            check(e.getLiftID() >= 1 && e.getLiftID() <= 40, "liftID out of range: " + e);
            check(e.getTime() >= 1 && e.getTime() <= 360, "time out of range: " + e);
            check(e.getSeasonID() == gen.getSeasonID(), "seasonID changed: " + e);
            check(e.getDayID() == gen.getDayID(), "dayID changed: " + e);
            maxResortSeen = Math.max(maxResortSeen, e.getResortID());
        }
        // Sanity: the whole configured resort range is actually used
        check(maxResortSeen == gen.getNumResorts(), "never generated resortID " + gen.getNumResorts());
        System.out.printf("  OK: %,d events, resorts 1-%d, seasonID=%d, dayID=%d%n",
                SAMPLES, gen.getNumResorts(), gen.getSeasonID(), gen.getDayID());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
