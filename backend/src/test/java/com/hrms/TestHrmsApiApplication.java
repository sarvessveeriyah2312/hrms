package com.hrms;

import org.springframework.boot.SpringApplication;

public class TestHrmsApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(HrmsApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
