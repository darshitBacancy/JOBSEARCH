package com.jobassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JobSearchAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobSearchAssistantApplication.class, args);
    }
}
