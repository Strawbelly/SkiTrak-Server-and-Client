package server;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import server.model.LiftRide;
import server.model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;

public class SkierServlet extends HttpServlet {
    private Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Get request path
        if (urlPath == null || urlPath.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
            return;
        }

        // Parse JSON body
        try {
            StringBuilder jsonBody = new StringBuilder();
            BufferedReader reader = req.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBody.append(line);
            }

            LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

            if (liftRide == null || !isLiftRideValid(liftRide)) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
                return;
            }

            res.setStatus(HttpServletResponse.SC_CREATED);

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
        }
    }

    private boolean isUrlValid(String[] urlParts) {
        if (urlParts.length != 8) {
            return false;
        }
        try {
            // Check URL format
            if (!urlParts[2].equals("seasons") ||
                    !urlParts[4].equals("days") ||
                    !urlParts[6].equals("skiers")) {
                return false;
            }
            // Check IDs
            return Integer.parseInt(urlParts[1]) > 0 &&
                    Integer.parseInt(urlParts[3]) > 0 &&
                    Integer.parseInt(urlParts[5]) > 0 &&
                    Integer.parseInt(urlParts[7]) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isLiftRideValid(LiftRide liftRide) {
        return liftRide.getTime() >= 1 &&
                liftRide.getTime() <= 360 &&
                liftRide.getLiftID() >= 1 &&
                liftRide.getLiftID() <= 40;
    }
}