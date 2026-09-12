package com.tagforge.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No id field, so a client cannot supply one. */
public record CreateDeviceRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name

) {
}
