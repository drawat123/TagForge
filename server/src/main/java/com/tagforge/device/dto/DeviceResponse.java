package com.tagforge.device.dto;

import com.tagforge.device.entity.Device;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(UUID id, String name, Instant createdAt) {

    public static DeviceResponse from(Device device) {
        return new DeviceResponse(device.getId(), device.getName(), device.getCreatedAt());
    }
}
