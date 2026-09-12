package com.tagforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TagForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TagForgeApplication.class, args);
    }
}
