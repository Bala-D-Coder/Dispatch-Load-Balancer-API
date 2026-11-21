package com.bunny.dispatch.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OrdersBatchRequest(
		@NotEmpty(message = "At least one order is required")
		List<@Valid OrderRequest> orders
) {
}
