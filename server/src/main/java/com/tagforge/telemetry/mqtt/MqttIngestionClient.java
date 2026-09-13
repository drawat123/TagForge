package com.tagforge.telemetry.mqtt;

import tools.jackson.databind.ObjectMapper;
import com.tagforge.telemetry.dto.TelemetryUpload;
import com.tagforge.telemetry.service.TelemetryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Subscribes to device telemetry and hands each message on.
 *
 * Callbacks run on Paho's network thread: blocking here stops the client reading
 * from the socket, so anything slow has to move off it.
 */
@Component
public class MqttIngestionClient implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestionClient.class);

    private final com.tagforge.telemetry.mqtt.MqttProperties properties;
    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private MqttClient client;

    public MqttIngestionClient(com.tagforge.telemetry.mqtt.MqttProperties properties,
                               TelemetryService telemetryService,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void connect() throws MqttException {
        client = new MqttClient(properties.brokerUrl(), properties.clientId(), new MemoryPersistence());
        client.setCallback(this);

        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        // false = the broker keeps our subscriptions across a reconnect, so messages
        // published while we were away are still delivered.
        options.setCleanStart(false);
        options.setSessionExpiryInterval(3600L);

        client.connect(options);
        client.subscribe(properties.topicFilter(), properties.qos());

        log.info("MQTT connected to {} and subscribed to {} at QoS {}",
                properties.brokerUrl(), properties.topicFilter(), properties.qos());
    }

    /**
     * Runs on Paho's network thread, and does a database write per value. That is
     * the naive path on purpose — it is the thing to measure before improving.
     *
     * Paho sends the PUBACK when this returns, including when it throws, so a
     * message that fails here is acked and gone. Manual acknowledgement is the
     * fix, once failures have somewhere to go.
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            UUID deviceId = deviceIdFrom(topic);
            TelemetryUpload upload = objectMapper.readValue(payload, TelemetryUpload.class);
            telemetryService.store(deviceId, upload);
            log.debug("Stored {} values for {}", upload.values().size(), deviceId);
        } catch (Exception e) {
            // Never propagate: an exception here would tear down the callback thread.
            log.warn("Discarding message on {}: {} ({})", topic, e.getMessage(), payload);
            log.debug("Cause", e);
        }
    }

    /**
     * v1/devices/{deviceId}/telemetry — segment 2.
     *
     * Trusting the topic is only sound once the broker's ACL pattern restricts a
     * device to publishing under its own id.
     */
    private static UUID deviceIdFrom(String topic) {
        String[] segments = topic.split("/");
        if (segments.length != 4) {
            throw new IllegalArgumentException("Unexpected topic shape: " + topic);
        }
        return UUID.fromString(segments[2]);
    }

    /**
     * Paho reconnects on its own; this records that the gap happened. Devices
     * publishing during it are the reason cleanStart is false.
     */
    @Override
    public void disconnected(MqttDisconnectResponse response) {
        log.warn("MQTT disconnected: {}", response.getReasonString());
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.error("MQTT error", exception);
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
        // Only relevant when publishing, which this client does not do.
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("MQTT connect complete (reconnect={}, uri={})", reconnect, serverUri);
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
        // Extended authentication is not used.
    }

    @PreDestroy
    void disconnect() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }
}
