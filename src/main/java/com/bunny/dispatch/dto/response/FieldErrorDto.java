package com.bunny.dispatch.dto.response;

public record FieldErrorDto(
		String field,
		Object rejectedValue,
		String message
) {
}
