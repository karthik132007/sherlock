package com.sherlock.app;

import com.sherlock.app.config.AppProperties;
import com.sherlock.app.controller.CaseController;
import com.sherlock.app.service.CaseService;
import com.sherlock.app.service.Neo4jGraphService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({AppProperties.class, Neo4jGraphService.class, CaseService.class, CaseController.class})
public class SherlockApplication {

	static {
		System.setProperty("spring.classformat.ignore", "true");
	}

	public static void main(String[] args) {
		SpringApplication.run(SherlockApplication.class, args);
	}
}
