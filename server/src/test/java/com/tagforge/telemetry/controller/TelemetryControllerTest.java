package com.tagforge.telemetry.controller;

import com.tagforge.device.entity.Device;
import com.tagforge.device.service.DeviceService;
import com.tagforge.telemetry.dto.TelemetryUpload;
import com.tagforge.telemetry.service.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each test creates its own device, so rows left by earlier runs in the shared
 * database cannot affect the assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelemetryControllerTest {

    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");
    private static final long T0 = 1757606400000L;   // 2025-09-11T16:00:00Z
    private static final String FROM = "2025-09-11T00:00:00Z";
    private static final String TO = "2025-09-12T00:00:00Z";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DeviceService deviceService;

    @Autowired
    TelemetryService telemetryService;

    private UUID deviceId;

    @BeforeEach
    void seed() {
        Device device = deviceService.create("test-" + UUID.randomUUID());
        deviceId = device.getId();

        telemetryService.store(deviceId, new TelemetryUpload(T0,
                Map.of("temperature", 23.5, "pressure", 4, "running", true, "status", "OK")));
        telemetryService.store(deviceId, new TelemetryUpload(T0 + 60_000,
                Map.of("temperature", 24.1, "pressure", 5)));
        telemetryService.store(deviceId, new TelemetryUpload(T0 + 120_000,
                Map.of("temperature", 25.9)));
    }

    @Test
    void latestReturnsTheMostRecentReadingPerKey() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry/latest", deviceId)
                        .param("keys", "temperature", "pressure", "running", "status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature.value").value(25.9))
                .andExpect(jsonPath("$.temperature.ts").value("2025-09-11T16:02:00Z"))
                // pressure stopped at 16:01, so its latest is older than temperature's
                .andExpect(jsonPath("$.pressure.value").value(5))
                .andExpect(jsonPath("$.pressure.ts").value("2025-09-11T16:01:00Z"))
                // the four typed columns collapse back into one untyped value field
                .andExpect(jsonPath("$.running.value").value(true))
                .andExpect(jsonPath("$.status.value").value("OK"));
    }

    @Test
    void latestOmitsKeysWithNoReadings() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry/latest", deviceId)
                        .param("keys", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    @Test
    void rangeGroupsByKeyNewestFirst() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry", deviceId)
                        .param("keys", "temperature", "pressure")
                        .param("from", FROM).param("to", TO).param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature", hasSize(3)))
                .andExpect(jsonPath("$.pressure", hasSize(2)))
                .andExpect(jsonPath("$.temperature[0].value").value(25.9))
                .andExpect(jsonPath("$.temperature[2].value").value(23.5));
    }

    /** The range is half-open, so a reading exactly at `to` is excluded. */
    @Test
    void rangeExcludesTheEndInstant() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry", deviceId)
                        .param("keys", "temperature")
                        .param("from", "2025-09-11T16:00:00Z")
                        .param("to", "2025-09-11T16:02:00Z")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature", hasSize(2)));
    }

    /**
     * Shared across keys, not applied per key — the two newest readings overall.
     *
     * Several keys share a timestamp, so ts alone does not decide which rows make
     * the cut; the query breaks ties on key, which is what makes this assertion
     * stable rather than whatever Postgres happened to return.
     */
    @Test
    void limitCapsTotalRowsAcrossKeys() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry", deviceId)
                        .param("keys", "temperature", "pressure")
                        .param("from", FROM).param("to", TO).param("limit", "2"))
                .andExpect(status().isOk())
                // newest is temperature at 16:02; next is 16:01, where pressure sorts first
                .andExpect(jsonPath("$.temperature", hasSize(1)))
                .andExpect(jsonPath("$.pressure", hasSize(1)));
    }

    @Test
    void missingLimitIsRejected() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry", deviceId)
                        .param("keys", "temperature").param("from", FROM).param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.parameter").value("limit"));
    }

    /** Was a 500 before the handler existed: a client error reported as a server fault. */
    @Test
    void limitAboveTheCapIsRejected() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry", deviceId)
                        .param("keys", "temperature").param("from", FROM).param("to", TO)
                        .param("limit", "99999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void unparseableTimestampIsRejected() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry", deviceId)
                        .param("keys", "temperature").param("from", "yesterday").param("to", TO)
                        .param("limit", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("from"));
    }

    @Test
    void unknownDeviceReturns404() throws Exception {
        mockMvc.perform(get("/devices/{id}/telemetry/latest", UUID.randomUUID())
                        .param("keys", "temperature"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
