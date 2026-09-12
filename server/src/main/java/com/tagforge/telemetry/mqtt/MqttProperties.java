package com.tagforge.telemetry.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param topicFilter wildcard subscription; the broker's ACL pattern is what makes
 *                    the device id in the topic trustworthy.
 * @param qos         1 = at least once, so a redelivery can duplicate a message.
 */
@ConfigurationProperties(prefix = "tagforge.mqtt")
public record MqttProperties(
        String brokerUrl,
        String clientId,
        String topicFilter,
        int qos
) {
}
