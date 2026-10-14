package client;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Draws a line chart of ingest throughput over time (swipes/second per bucket)
 * to a PNG using only the JDK (java.awt + ImageIO), so no extra dependency.
 */
public final class ThroughputChart {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 560;
    private static final int LEFT = 90, RIGHT = 40, TOP = 60, BOTTOM = 70;

    private ThroughputChart() {}

    /**
     * @param bucketStartSeconds elapsed time (s since test start) at the start of each bucket
     * @param swipesPerSecond    throughput of each bucket
     */
    public static void write(double[] bucketStartSeconds, double[] swipesPerSecond,
                             int bucketSeconds, File out) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        int plotW = WIDTH - LEFT - RIGHT;
        int plotH = HEIGHT - TOP - BOTTOM;

        double xMax = Math.max(bucketSeconds,
                bucketStartSeconds.length == 0 ? 0 : bucketStartSeconds[bucketStartSeconds.length - 1]);
        double yMax = 0;
        for (double v : swipesPerSecond) yMax = Math.max(yMax, v);
        yMax = niceCeiling(yMax * 1.1);

        // Title
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        drawCentered(g, "Ingest throughput over time (" + bucketSeconds + "-second buckets)", WIDTH / 2, 32);

        // Grid lines and Y-axis labels
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int yTicks = 5;
        for (int i = 0; i <= yTicks; i++) {
            double value = yMax * i / yTicks;
            int y = TOP + plotH - (int) Math.round(plotH * (value / yMax));
            g.setColor(new Color(225, 225, 225));
            g.drawLine(LEFT, y, LEFT + plotW, y);
            g.setColor(Color.DARK_GRAY);
            String label = String.format("%,.0f", value);
            g.drawString(label, LEFT - 8 - g.getFontMetrics().stringWidth(label), y + 4);
        }

        // X-axis labels, one per bucket boundary (thinned if there are many)
        int labelStep = Math.max(1, (int) Math.ceil(bucketStartSeconds.length / 15.0));
        for (int i = 0; i < bucketStartSeconds.length; i += labelStep) {
            int x = xToPixel(bucketStartSeconds[i], xMax, plotW);
            g.setColor(Color.DARK_GRAY);
            g.drawLine(x, TOP + plotH, x, TOP + plotH + 5);
            drawCentered(g, String.format("%.0f", bucketStartSeconds[i]), x, TOP + plotH + 20);
        }

        // Axes
        g.setColor(Color.BLACK);
        g.drawLine(LEFT, TOP, LEFT, TOP + plotH);
        g.drawLine(LEFT, TOP + plotH, LEFT + plotW, TOP + plotH);

        // Axis titles
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        drawCentered(g, "Elapsed time (seconds since test start, bucket start)", LEFT + plotW / 2, HEIGHT - 20);
        Graphics2D gy = (Graphics2D) g.create();
        gy.rotate(-Math.PI / 2);
        drawCentered(gy, "Throughput (swipes/second)", -(TOP + plotH / 2), 24);
        gy.dispose();

        // The line and points
        g.setColor(new Color(31, 119, 180));
        g.setStroke(new BasicStroke(2.5f));
        int prevX = -1, prevY = -1;
        for (int i = 0; i < swipesPerSecond.length; i++) {
            int x = xToPixel(bucketStartSeconds[i], xMax, plotW);
            int y = TOP + plotH - (int) Math.round(plotH * (swipesPerSecond[i] / yMax));
            if (prevX >= 0) g.drawLine(prevX, prevY, x, y);
            g.fillOval(x - 4, y - 4, 8, 8);
            prevX = x;
            prevY = y;
        }

        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static int xToPixel(double x, double xMax, int plotW) {
        return LEFT + (int) Math.round(plotW * (x / xMax));
    }

    private static void drawCentered(Graphics2D g, String s, int x, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, x - fm.stringWidth(s) / 2, y);
    }

    /** Rounds up to 1, 2 or 5 times a power of ten, for readable axis ticks. */
    private static double niceCeiling(double v) {
        if (v <= 0) return 1;
        double pow = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 2, 5, 10}) {
            if (m * pow >= v) return m * pow;
        }
        return 10 * pow;
    }
}
