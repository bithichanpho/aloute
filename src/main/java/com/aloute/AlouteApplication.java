package com.aloute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AlouteApplication {

	public static void main(String[] args) {
		SpringApplication.run(AlouteApplication.class, args);
	}

}
