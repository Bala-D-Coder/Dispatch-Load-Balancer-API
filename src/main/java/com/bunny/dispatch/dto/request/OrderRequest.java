package com.bunny.dispatch.dto.request;

import com.bunny.dispatch.entity.common.Priority;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderRequest(
		@NotBlank(message = "orderId must not be blank")
		@Size(max = 64, message = "orderId must not exceed 64 characters")
		String orderId,

		@NotNull(message = "latitude is required")
		@DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
		@DecimalMax(value = "90.0", message = "latitude must be between -90 and 90")
		Double latitude,

		@NotNull(message = "longitude is required")
		@DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
		@DecimalMax(value = "180.0", message = "longitude must be between -180 and 180")
		Double longitude,

		@NotBlank(message = "address must not be blank")
		@Size(max = 512, message = "address must not exceed 512 characters")
		String address,

		@NotNull(message = "packageWeight is required")
		@Positive(message = "packageWeight must be greater than 0")
		@Max(value = 10000, message = "packageWeight must not exceed 10000 kg")
		Integer packageWeight,

		@NotNull(message = "priority is required and must be HIGH, MEDIUM or LOW")
		Priority priority
) {
}
