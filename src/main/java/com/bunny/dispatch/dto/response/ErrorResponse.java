package com.bunny.dispatch.dto.response;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
		Instant timestamp,
		int status,
		String error,
		String code,
		String message,
		String path,
		List<FieldErrorDto> fieldErrors
) {

	public static ErrorResponse of(int status, String error, String code, String message, String path) {
		return new ErrorResponse(Instant.now(), status, error, code, message, path, List.of());
	}

	public static ErrorResponse withFields(int status, String error, String code, String message, String path,
			List<FieldErrorDto> fieldErrors) {
		return new ErrorResponse(Instant.now(), status, error, code, message, path, fieldErrors);
	}

}
