package com.tagforge.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class DeviceProvisioning implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceProvisioning.class);

    /** A load tool must never wait forever on the thing it is measuring. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final URI devicesUri;

    public DeviceProvisioning(String serverUrl) {
        this.devicesUri = URI.create(serverUrl + "/devices");
    }

    private UUID createDevice(int index) throws IOException, InterruptedException {
        var request = HttpRequest
                .newBuilder()
                .uri(devicesUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"device-" + index + "\"}"))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IOException("Device creation failed: " + response.statusCode() + " " + response.body());
        }

        return extractDeviceId(response.body());
    }

    /**
     * The missing-field check sits outside the try on purpose: inside, its own
     * exception would be caught below and rewrapped as a parse failure, reporting
     * the wrong cause.
     */
    private UUID extractDeviceId(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Response was not JSON: " + responseBody, e);
        }

        JsonNode idNode = root.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new IllegalStateException("Missing 'id' field in response: " + responseBody);
        }
        return objectMapper.treeToValue(idNode, UUID.class);
    }

    /**
     * Sequential on purpose: each request is timed end to end, which measures the
     * endpoint's real per-request latency. That number is interesting in itself,
     * since creating a device issues a SELECT before the INSERT.
     */
    public List<UUID> createDevices(int numOfDevice) throws IOException, InterruptedException {
        log.info("Starting provisioning of {} devices...", numOfDevice);

        long start = System.nanoTime();
        List<UUID> deviceIds = new ArrayList<>(numOfDevice);

        for (int i = 0; i < numOfDevice; i++) {
            deviceIds.add(createDevice(i));
        }

        // Averaged in nanoseconds and converted last: dividing truncated
        // milliseconds reports 0.00 for anything faster than a millisecond.
        long totalNanos = System.nanoTime() - start;
        long totalMs = TimeUnit.NANOSECONDS.toMillis(totalNanos);
        double avgMs = numOfDevice > 0 ? totalNanos / (double) numOfDevice / 1_000_000 : 0.0;

        log.info("Provisioned {} devices in {} ms (avg: {} ms/device)", numOfDevice, totalMs,
                String.format("%.2f", avgMs));

        return deviceIds;
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
