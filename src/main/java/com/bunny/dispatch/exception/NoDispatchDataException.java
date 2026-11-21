package com.bunny.dispatch.exception;

/** Both orders and vehicles tables are empty — there is nothing to plan from (maps to 404). */
public class NoDispatchDataException extends RuntimeException {

	public NoDispatchDataException(String message) {
		super(message);
	}

}
