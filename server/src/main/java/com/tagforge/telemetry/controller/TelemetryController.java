package com.tagforge.telemetry.controller;

import com.tagforge.telemetry.dto.TelemetryPoint;
import com.tagforge.telemetry.service.TelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two endpoints rather than one with optional parameters, because the costs are
 * not comparable: latest reads one row per key, range can read a whole week.
 */
@RestController
@RequestMapping("/devices/{deviceId}/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @Operation(summary = "Latest reading for each requested key")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Keys with no readings are omitted"),
            @ApiResponse(responseCode = "404", description = "No device with that id")
    })
    @GetMapping("/latest")
    public ResponseEntity<Map<String, TelemetryPoint>> latest(
            @PathVariable UUID deviceId,
            @RequestParam @NotEmpty String[] keys) {

        return ResponseEntity.ok(telemetryService.latest(deviceId, keys));
    }

    /**
     * limit is mandatory and capped. Retention is seven days at 1 Hz, so an
     * uncapped query could ask for 604,800 points per key -- large enough that
     * a single careless request would dominate any load measurement.
     */
    @Operation(summary = "Readings in a time range, grouped by key",
            description = "Range is half-open: from inclusive, to exclusive. "
                    + "limit is shared across all requested keys, not applied per key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Newest first"),
            @ApiResponse(responseCode = "400", description = "Missing or out-of-range parameters"),
            @ApiResponse(responseCode = "404", description = "No device with that id")
    })
    @GetMapping
    public ResponseEntity<Map<String, List<TelemetryPoint>>> range(
            @PathVariable UUID deviceId,
            @RequestParam @NotEmpty String[] keys,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam @Min(1) @Max(10_000) int limit) {

        return ResponseEntity.ok(telemetryService.range(deviceId, keys, from, to, limit));
    }
}
