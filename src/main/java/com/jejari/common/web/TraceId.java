package com.jejari.common.web;

import org.slf4j.MDC;

/** 현재 요청의 traceId. TraceIdFilter가 MDC에 넣고 요청이 끝나면 지웁니다. */
public final class TraceId {

	public static final String MDC_KEY = "traceId";
	public static final String HEADER = "X-Trace-Id";

	private TraceId() {
	}

	/** 요청 밖(스케줄러·Kafka 소비 등)에서는 null일 수 있습니다. */
	public static String current() {
		return MDC.get(MDC_KEY);
	}
}
