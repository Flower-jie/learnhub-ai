package com.flower.learnhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppSettings.class)
public class LearnHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(LearnHubApplication.class, args);
    }
}
