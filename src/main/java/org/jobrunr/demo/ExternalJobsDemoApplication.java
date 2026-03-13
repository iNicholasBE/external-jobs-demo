package org.jobrunr.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExternalJobsDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExternalJobsDemoApplication.class, args);
    }
}
