package com.bunny.dispatch.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record VehicleRequest(
		@NotBlank(message = "vehicleId must not be blank")
		@Size(max = 64, message = "vehicleId must not exceed 64 characters")
		String vehicleId,

		@NotNull(message = "capacity is required")
		@Positive(message = "capacity must be greater than 0")
		@Max(value = 100000, message = "capacity must not exceed 100000 kg")
		Integer capacity,

		@NotNull(message = "currentLatitude is required")
		@DecimalMin(value = "-90.0", message = "currentLatitude must be between -90 and 90")
		@DecimalMax(value = "90.0", message = "currentLatitude must be between -90 and 90")
		Double currentLatitude,

		@NotNull(message = "currentLongitude is required")
		@DecimalMin(value = "-180.0", message = "currentLongitude must be between -180 and 180")
		@DecimalMax(value = "180.0", message = "currentLongitude must be between -180 and 180")
		Double currentLongitude,

		@NotBlank(message = "currentAddress must not be blank")
		@Size(max = 512, message = "currentAddress must not exceed 512 characters")
		String currentAddress
) {
}
