/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.sample.viewsjava;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

import io.devconsole.DevConsole;
import io.devconsole.locationping.LocationPingOutcome;
import io.devconsole.locationping.LocationPingSchema;
import io.devconsole.locationping.LocationPingStream;
import io.devconsole.locationping.LocationPingTracking;
import io.devconsole.mocks.MockAction;
import io.devconsole.mocks.MockRule;
import io.devconsole.mocks.MockScope;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class SampleLocationPings {
    static final String STREAM_NAME = "ride";

    private static final String PING_PATH = "/v1/rides/pings";
    private static final String PING_URL = "https://example.test" + PING_PATH;
    private static final String STATUS_HEADER = "X-Sample-Ping-Status";
    private static final String ACCEPTED_RULE_ID = "java-sample-ping-accepted";
    private static final String REJECTED_RULE_ID = "java-sample-ping-rejected";
    private static final String RIDE_ID = "sample-ride-42";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int OUTCOMES_IN_CYCLE = 4;
    private static final double METERS_BETWEEN_PINGS = 120.0;
    private static final long FIX_AGE_MILLIS = 850;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double[][] ROUTE = {
            {23.793644, 90.410654}, {23.794589, 90.413653}, {23.794912, 90.414259},
            {23.793998, 90.414788}, {23.790881, 90.415958}, {23.787626, 90.416817},
            {23.785072, 90.417069}, {23.780999, 90.416727}, {23.780156, 90.416931},
            {23.778169, 90.416700},
    };

    private final OkHttpClient client;
    private int pingsSent;

    SampleLocationPings(OkHttpClient client) {
        this.client = client;
    }

    static LocationPingTracking tracking() {
        return new LocationPingTracking(
                Collections.singletonList(new LocationPingStream(STREAM_NAME, PING_PATH)),
                new LocationPingSchema("pings", "lat", "lng", "device_time", "fix_time", "ride_ids", "app_state", "Ride"),
                LocationPingTracking.DEFAULT_MAX_STORAGE_BYTES
        );
    }

    static void installMockResponses() {
        DevConsole.mockEngine().upsert(mockAnswering(REJECTED_RULE_ID, 1,
                Collections.singletonMap(STATUS_HEADER, "401"), 401));
        DevConsole.mockEngine().upsert(mockAnswering(ACCEPTED_RULE_ID, 0, Collections.emptyMap(), 200));
    }

    String sendNextPing() {
        int pingIndex = pingsSent++;
        String body = pingBodyAt(pingIndex);
        String outcome;
        switch (pingIndex % OUTCOMES_IN_CYCLE) {
            case 0:
                outcome = sendThroughNetwork(body, false);
                break;
            case 1:
                outcome = sendThroughNetwork(body, true);
                break;
            case 2:
                DevConsole.recordLocationPing(PING_URL, body, LocationPingOutcome.droppedOnDevice());
                outcome = "dropped on device";
                break;
            default:
                DevConsole.recordLocationPing(PING_URL, body, LocationPingOutcome.noConnection());
                outcome = "not sent, offline";
                break;
        }
        String recording = DevConsole.isLocationPingTrackingEnabled(STREAM_NAME)
                ? "recorded"
                : "not recorded -- turn on the ride stream in More → Location pings";
        return "Location ping #" + (pingIndex + 1) + ": " + outcome + " (" + recording + ")";
    }

    private String sendThroughNetwork(String body, boolean rejected) {
        installMockResponses();
        Request.Builder request = new Request.Builder().url(PING_URL).post(RequestBody.create(body, JSON));
        if (rejected) request.header(STATUS_HEADER, "401");
        try (Response response = client.newCall(request.build()).execute()) {
            return "HTTP " + response.code();
        } catch (IOException error) {
            return "failed: " + error.getMessage();
        }
    }

    private String pingBodyAt(int pingIndex) {
        long deviceTime = System.currentTimeMillis();
        double[] position = positionAfter(pingIndex * METERS_BETWEEN_PINGS);
        String appState = pingIndex % 2 == 0 ? "FOREGROUND" : "BACKGROUND";
        return String.format(Locale.US,
                "{\"ride_ids\":[\"%s\"],\"app_state\":\"%s\",\"battery\":%d,\"pings\":[{\"lat\":%.6f,\"lng\":%.6f,"
                        + "\"device_time\":%d,\"fix_time\":%d,\"accuracy\":4.3,\"speed\":7.1}]}",
                RIDE_ID, appState, 100 - pingIndex % 100, position[0], position[1], deviceTime,
                deviceTime - FIX_AGE_MILLIS);
    }

    private static double[] positionAfter(double meters) {
        double routeLength = routeLengthMeters();
        double lap = routeLength * 2;
        double inLap = meters % lap;
        double metersLeft = inLap <= routeLength ? inLap : lap - inLap;
        for (int leg = 0; leg < ROUTE.length - 1; leg++) {
            double legLength = metersBetween(ROUTE[leg], ROUTE[leg + 1]);
            if (metersLeft <= legLength) {
                double share = legLength == 0 ? 0 : metersLeft / legLength;
                return new double[]{
                        ROUTE[leg][0] + (ROUTE[leg + 1][0] - ROUTE[leg][0]) * share,
                        ROUTE[leg][1] + (ROUTE[leg + 1][1] - ROUTE[leg][1]) * share,
                };
            }
            metersLeft -= legLength;
        }
        return ROUTE[ROUTE.length - 1];
    }

    private static double routeLengthMeters() {
        double total = 0;
        for (int leg = 0; leg < ROUTE.length - 1; leg++) total += metersBetween(ROUTE[leg], ROUTE[leg + 1]);
        return total;
    }

    private static double metersBetween(double[] from, double[] to) {
        double latDelta = Math.toRadians(to[0] - from[0]);
        double lngDelta = Math.toRadians(to[1] - from[1]);
        double haversine = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(Math.toRadians(from[0])) * Math.cos(Math.toRadians(to[0]))
                * Math.sin(lngDelta / 2) * Math.sin(lngDelta / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    private static MockRule mockAnswering(String id, int priority, java.util.Map<String, String> headers, int status) {
        return new MockRule(
                id,
                priority,
                "POST",
                null,
                null,
                PING_PATH,
                Collections.emptyMap(),
                headers,
                null,
                MockScope.SESSION,
                new MockAction.StaticResponse(status, "{\"accepted\":" + (status == 200) + "}", Collections.emptyMap()),
                null
        );
    }
}
