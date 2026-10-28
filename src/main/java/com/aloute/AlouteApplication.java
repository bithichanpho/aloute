package com.aloute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AlouteApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AlouteApplication.class);
        application.addListeners(new com.aloute.config.LocalServicesAutoStarter());
        application.run(args);
    }
}
