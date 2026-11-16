package com.jejari.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 테스트용 시계. 시간을 고정하거나 앞으로 돌릴 수 있습니다. */
public class MutableClock extends Clock {

	public static final Instant DEFAULT = Instant.parse("2026-11-16T01:00:00Z"); // 10:00 KST

	private final ZoneId zone;
	private Instant now;

	public MutableClock() {
		this(DEFAULT, ZoneId.of("Asia/Seoul"));
	}

	public MutableClock(Instant now, ZoneId zone) {
		this.now = now;
		this.zone = zone;
	}

	public void set(Instant instant) {
		this.now = instant;
	}

	public void advance(Duration duration) {
		this.now = now.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(now, zone);
	}

	@Override
	public Instant instant() {
		return now;
	}
}
