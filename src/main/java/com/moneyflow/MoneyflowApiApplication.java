package com.moneyflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class MoneyflowApiApplication {
	public static void main(String[] args) {
        SpringApplication.run(MoneyflowApiApplication.class, args);
	}
}
