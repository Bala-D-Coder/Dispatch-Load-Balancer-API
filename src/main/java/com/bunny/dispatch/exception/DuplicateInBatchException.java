package com.bunny.dispatch.exception;

/** Same natural key appearing twice within a single request batch (D8) — a user error, never persisted. */
public class DuplicateInBatchException extends RuntimeException {

	public DuplicateInBatchException(String message) {
		super(message);
	}

}
