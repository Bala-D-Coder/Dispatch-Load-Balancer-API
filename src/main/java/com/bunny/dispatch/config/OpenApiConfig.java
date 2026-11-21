package com.bunny.dispatch.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI is served at {@code /swagger-ui.html} and the machine-readable contract at
 * {@code /v3/api-docs} (that URL can be imported straight into Postman). Per-endpoint text
 * lives on {@code DispatchController}'s {@code @Operation} annotations, next to the code it
 * describes, so documentation and behaviour cannot drift apart.
 */
@Configuration
public class OpenApiConfig {

	private static final String DESCRIPTION = """
			Assigns delivery orders to capacity-limited vehicles: HIGH priority first, then the \
			nearest drop by Haversine distance from each vehicle's current route tip.

			Notes for API consumers:
			* GET /api/dispatch/plan recomputes from the database on every call - there is no cache.
			* totalDistance is a ROUND TRIP (vehicle start -> stops -> start); the spec's example \
			values are illustrative one-way figures.
			* totalDistanceKm and legs[] are additive fields for hand-verifying the distance.
			* Re-posting an existing orderId/vehicleId upserts; a duplicate WITHIN one batch is 400.
			* DELETE /api/dispatch/data is an additive reset helper, not part of the original spec.
			* Every error uses the same body; the 'code' field is the stable machine-readable key.
			""";

	@Bean
	OpenAPI dispatchOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Dispatch Load Balancer API")
				.version("1.0.0")
				.description(DESCRIPTION));
	}

}
