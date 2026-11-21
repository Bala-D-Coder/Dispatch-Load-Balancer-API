package com.bunny.dispatch.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record VehiclesBatchRequest(
		@NotEmpty(message = "At least one vehicle is required")
		List<@Valid VehicleRequest> vehicles
) {
}
