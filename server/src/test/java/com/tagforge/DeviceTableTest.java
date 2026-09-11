package com.tagforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeviceTableTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void flywayCreatesTheDeviceTableAndRowsRoundTrip() {
        UUID id = UUID.randomUUID();

        jdbc.sql("INSERT INTO device (id, name) VALUES (?, ?)")
            .params(id, "press-01")
            .update();

        String name = jdbc.sql("SELECT name FROM device WHERE id = ?")
            .param(id)
            .query(String.class)
            .single();

        assertThat(name).isEqualTo("press-01");
    }
}
