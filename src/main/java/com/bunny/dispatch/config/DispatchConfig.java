package com.bunny.dispatch.config;

import com.bunny.dispatch.service.dispatch.handler.AssignmentStrategy;
import com.bunny.dispatch.service.dispatch.handler.GreedyAssignmentStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Algorithm swap point: the handler layer is Spring-free, so its beans are declared here. */
@Configuration
public class DispatchConfig {

	@Bean
	public AssignmentStrategy assignmentStrategy() {
		return new GreedyAssignmentStrategy();
	}

}
