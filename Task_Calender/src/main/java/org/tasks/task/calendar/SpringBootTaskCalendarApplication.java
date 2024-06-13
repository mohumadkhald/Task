package org.tasks.task.calendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories(basePackages = "org.tasks.task.calendar.events")
@EntityScan(basePackages = "org.tasks.task.calendar.model")
@SpringBootApplication
public class SpringBootTaskCalendarApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringBootTaskCalendarApplication.class, args);
	}

}
