package com.tagforge.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class VirtualDevices implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VirtualDevices.class);
    private static final String DEFAULT_BROKER_URL = "tcp://localhost:1883";

    private final String brokerUrl;
    private final int tagCount;
    private final long intervalMs;
    private final boolean synchronised;
    private final int qos;
    private final Metrics metrics;

    /**
     * Built once instead of on every publish: "tag_" + i allocated a string per tag
     * per message, which at 10,000 devices is 500,000 short-lived strings a second.
     * Garbage in the load generator becomes GC pauses, and a GC pause is
     * indistinguishable from server latency in the results.
     */
    private final String[] tagNames;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final List<MqttAsyncClient> clients = new ArrayList<>();
    private final List<Thread> deviceThreads = new ArrayList<>();
    private volatile boolean running = false;

    public VirtualDevices(String brokerUrl, int tagCount, long intervalMs,
                          boolean synchronised, int qos, Metrics metrics) {
        this.brokerUrl = brokerUrl;
        this.tagCount = tagCount;
        this.intervalMs = intervalMs;
        this.synchronised = synchronised;
        this.qos = qos;
        this.metrics = metrics;
        this.tagNames = new String[tagCount];
        for (int i = 0; i < tagCount; i++) {
            tagNames[i] = "tag_" + i;
        }
    }

    public VirtualDevices(Metrics metrics) {
        this(DEFAULT_BROKER_URL, 50, 1000L, false, 1, metrics);
    }

    /**
     * MqttAsyncClient, not MqttClient. The synchronous client's publish() blocks
     * until the PUBACK arrives, which makes published and acknowledged the same
     * number by construction — in-flight could never be non-zero, and a slow broker
     * would show up as the simulator quietly sending less rather than as
     * backpressure. It also avoids blocking inside Paho's synchronized internals,
     * which on Java 21 pins the carrier thread and would cap concurrency at roughly
     * the core count no matter how many devices are started.
     */
    public MqttAsyncClient createClient(UUID deviceId) throws MqttException {
        MqttAsyncClient client = new MqttAsyncClient(brokerUrl, deviceId.toString(), new MemoryPersistence());

        MqttConnectionOptions options = new MqttConnectionOptions();
        // No session to resume: a client that replays missed publishes would hide
        // the failures this tool exists to record.
        options.setCleanStart(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);

        client.connect(options).waitForCompletion();
        return client;
    }

    public void startPublish(List<UUID> deviceIds) throws InterruptedException {
        running = true;
        log.info("Starting {} virtual devices (interval: {} ms, arrival: {}, qos: {}, tags: {})...",
                deviceIds.size(), intervalMs, synchronised ? "synchronised" : "spread", qos, tagCount);

        int connectedCount = 0;
        for (UUID deviceId : deviceIds) {
            try {
                MqttAsyncClient client = createClient(deviceId);
                clients.add(client);

                Thread thread = Thread.ofVirtual()
                        .name("device-" + deviceId)
                        .start(() -> runDevicePublishLoop(deviceId, client));
                deviceThreads.add(thread);
                connectedCount++;
            } catch (MqttException e) {
                // Failure to connect one device must not prevent others from running.
                log.error("Failed to connect device {}: {}", deviceId, e.getMessage());
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("too many open files")) {
                    log.error("Hit OS file descriptor limit! Increase with 'ulimit -n 65536'");
                }
            }
        }

        log.info("Connected {}/{} virtual devices. Publishing telemetry... (Press Ctrl+C to stop)",
                connectedCount, deviceIds.size());
        log.info("The first few seconds are JIT and connection warmup, not steady state.");

        while (running && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(1000);
            log.info(metrics.reportLine());
        }
    }

    private void runDevicePublishLoop(UUID deviceId, MqttAsyncClient client) {
        String topic = "v1/devices/" + deviceId + "/telemetry";
        try {
            // Spread: a random offset within the interval, so arrivals are even.
            // Synchronised: every device on the same tick, which is what a broker
            // restart produces and is roughly 100x the load in a 10 ms window.
            long initialOffset = synchronised ? 0 : ThreadLocalRandom.current().nextLong(intervalMs);
            if (initialOffset > 0) {
                Thread.sleep(initialOffset);
            }

            long nextDeadline = System.currentTimeMillis();
            long step = 0;

            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    long ts = System.currentTimeMillis();
                    Map<String, Object> values = generateValues(step++);
                    byte[] payload = objectMapper.writeValueAsBytes(Map.of("ts", ts, "values", values));

                    MqttMessage message = new MqttMessage(payload);
                    message.setQos(qos);
                    message.setRetained(false);

                    long publishedAt = System.nanoTime();
                    metrics.recordPublished();
                    // Returns immediately; the ack arrives on Paho's callback thread.
                    client.publish(topic, message, null, ackListener(deviceId, publishedAt));
                } catch (MqttException e) {
                    metrics.recordFailed();
                    if (running) {
                        log.warn("Device {} publish failed: {}", deviceId, e.getMessage());
                    }
                }

                // Sleep to a deadline, not for a duration: sleeping a fixed interval
                // after work that took time makes the real period longer than the
                // target, and the drift compounds.
                nextDeadline += intervalMs;
                long sleepTime = nextDeadline - System.currentTimeMillis();
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                } else {
                    // Behind schedule. Reset rather than fire a burst of backdated
                    // publishes, but record it — a device missing half its intervals
                    // would otherwise be indistinguishable from one keeping up.
                    metrics.recordMissedInterval();
                    nextDeadline = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) {
                log.error("Device {} encountered unexpected error in publish loop: {}", deviceId, e.getMessage());
            }
        }
    }

    private MqttActionListener ackListener(UUID deviceId, long publishedAt) {
        return new MqttActionListener() {
            @Override
            public void onSuccess(IMqttToken token) {
                metrics.recordAcknowledged(System.nanoTime() - publishedAt);
            }

            @Override
            public void onFailure(IMqttToken token, Throwable cause) {
                metrics.recordFailed();
                if (running) {
                    log.warn("Device {} publish not acknowledged: {}", deviceId, cause.getMessage());
                }
            }
        };
    }

    private Map<String, Object> generateValues(long step) {
        Map<String, Object> values = new LinkedHashMap<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            double val = 50.0 + 20.0 * Math.sin(step * 0.1 + i);
            values.put(tagNames[i], Math.round(val * 100.0) / 100.0);
        }
        return values;
    }

    @Override
    public void close() {
        running = false;
        for (Thread thread : deviceThreads) {
            thread.interrupt();
        }
        for (MqttAsyncClient client : clients) {
            try {
                if (client.isConnected()) {
                    client.disconnect().waitForCompletion();
                }
                client.close();
            } catch (MqttException e) {
                log.warn("Error disconnecting client {}: {}", client.getClientId(), e.getMessage());
            }
        }
        clients.clear();
        deviceThreads.clear();
    }
}
