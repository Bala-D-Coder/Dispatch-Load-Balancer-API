package com.bunny.dispatch.exception;

import com.bunny.dispatch.dto.response.ErrorResponse;
import com.bunny.dispatch.dto.response.FieldErrorDto;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * The entire error matrix in one class (E1-E7 of the implementation plan). Every failure —
 * from any endpoint — answers with the same 7-key {@link ErrorResponse}, so clients can rely
 * on the stable machine-readable {@code code} field.
 *
 * <p>One advice for the whole app — NOT scoped by basePackage: transport failures like 405
 * or unknown-path 404 are thrown before any controller is matched, and a package-scoped
 * advice cannot apply when there is no handler to attribute the exception to.
 *
 * <p>The catch-all deliberately never echoes the exception message: internals (SQL, driver
 * details) must not reach the client; the full stack goes to the log instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** E1 — bean validation on the request body. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
		 HttpServletRequest request) {
		List<FieldErrorDto> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> new FieldErrorDto(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
				.toList();
		ErrorResponse body = ErrorResponse.withFields(
				HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
				"VALIDATION_FAILED",
				"Validation failed for " + fieldErrors.size() + " field(s)",
				request.getRequestURI(), fieldErrors);
		return ResponseEntity.badRequest().body(body);
	}

	/** E2 — same natural key twice in one batch (D8). */
	@ExceptionHandler(DuplicateInBatchException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateInBatch(DuplicateInBatchException ex,
		 HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "DUPLICATE_IN_BATCH", ex.getMessage(), request);
	}

	/** E3 — plan requested with both tables empty. */
	@ExceptionHandler(NoDispatchDataException.class)
	public ResponseEntity<ErrorResponse> handleNoDispatchData(NoDispatchDataException ex,
		 HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "NO_DISPATCH_DATA", ex.getMessage(), request);
	}

	/** E4 — malformed JSON or unknown fields; fixed text, never the raw Jackson message. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
		 HttpServletRequest request) {
		log.debug("Unreadable request body for {}", request.getRequestURI(), ex);
		return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
				"Malformed or unreadable JSON request body", request);
	}

	/** E5 — non-JSON Content-Type on POST. */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
		 HttpServletRequest request) {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
				"Content-Type must be application/json", request);
	}

	/** E6 — e.g. DELETE on /orders. */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
		 HttpServletRequest request) {
		return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
				"Method " + ex.getMethod() + " not supported for path " + request.getRequestURI(), request);
	}

	/** E6b — unmapped path under our base; must land BEFORE the catch-all as a 404, not a 500. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
		 HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND",
				"No endpoint found for path " + request.getRequestURI(), request);
	}

	/** E7 — backstop; message is fixed so internals never leak. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"Internal server error", request);
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code,
		 String message, HttpServletRequest request) {
		ErrorResponse body = ErrorResponse.of(status.value(), status.getReasonPhrase(),
				code, message, request.getRequestURI());
		return ResponseEntity.status(status).body(body);
	}

}
