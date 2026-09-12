package com.tagforge.telemetry.mqtt;

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
    private MqttClient client;

    public MqttIngestionClient(com.tagforge.telemetry.mqtt.MqttProperties properties) {
        this.properties = properties;
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

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.info("MQTT message on {} ({} bytes, qos {}, duplicate {}): {}",
                topic, message.getPayload().length, message.getQos(), message.isDuplicate(), payload);
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
