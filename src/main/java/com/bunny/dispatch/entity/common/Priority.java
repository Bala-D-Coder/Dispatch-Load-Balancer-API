package com.bunny.dispatch.entity.common;

public enum Priority {

	HIGH,
	MEDIUM,
	LOW;

	public int rank() {
		return ordinal();
	}

}
