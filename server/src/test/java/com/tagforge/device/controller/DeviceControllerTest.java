package com.tagforge.device.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real context, real database, no network socket. */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceControllerTest {

    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    @Autowired
    MockMvc mockMvc;

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        mockMvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"press-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.name").value("press-01"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                // Location must point at the device that was actually created, not merely exist.
                .andExpect(result -> {
                    String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
                    header().string("Location", endsWith("/devices/" + id)).match(result);
                });
    }

    @Test
    void createdDeviceCanBeFetched() throws Exception {
        String created = mockMvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"boiler-07\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(get("/devices/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("boiler-07"));
    }

    @Test
    void unknownIdReturns404ProblemDetail() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/devices/{id}", unknown))
                .andExpect(status().isNotFound())
                // Proves ApiExceptionHandler ran, rather than the default error page.
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Device not found"))
                .andExpect(jsonPath("$.deviceId").value(unknown.toString()));
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));
    }

    /** @NotBlank rejects null too — confirmed rather than assumed. */
    @Test
    void missingNameIsRejectedLikeABlankOne() throws Exception {
        mockMvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));
    }
}
