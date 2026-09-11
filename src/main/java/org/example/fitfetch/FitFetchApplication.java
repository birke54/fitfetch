package org.example.fitfetch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FitFetchApplication {

	public static void main(String[] args) {
		SpringApplication.run(FitFetchApplication.class, args);
	}

}
