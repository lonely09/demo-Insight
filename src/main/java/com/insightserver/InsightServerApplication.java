package com.insightserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class InsightServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsightServerApplication.class, args);
	}

}
