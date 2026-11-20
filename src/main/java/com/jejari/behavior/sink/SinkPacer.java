package com.jejari.behavior.sink;

/**
 * 행동 로그 적재 속도 상한 (소비자 스레드 전체 합). 밀린 로그를 따라잡을 때 최대 속도로 쏟아 넣지 않게 한다.
 *
 * <p><b>왜</b> (2026-11-20 실측, 4코어 하나에 앱·Oracle·MongoDB·Kafka·k6, 서버 VM과 같은 구성):
 * 적재는 초당 약 2만~3만 건까지 된다. MongoDB가 멈춘 사이 50만 건이 밀렸다가 예매 150명이 몰리는 중에 복구되면,
 * 그 속도로 따라잡느라 CPU가 100%가 되어 좌석 선점 p95가 예매만 할 때보다 약 3배(217~222 → 631~633ms) 느려졌다.
 * DB 커넥션 대기는 0 — 원인은 커넥션 풀이 아니라 CPU.
 * <pre>
 *            좌석 선점 p95 (예매만 → 따라잡는 중)   50만 건 따라잡는 시간
 * 상한 없음   217~222 → 631~633ms (약 2.9배)       약 20초
 * 5,000      143~226 → 385~420ms (약 1.9~2.7배)    약 100초
 * 2,000      264 → 339ms (약 1.3배)                약 4분
 * </pre>
 * 평소 유입은 상한보다 한참 아래다: 예매 150명이 10초 안에 몰릴 때 초당 최고 약 100건(1인 약 4건) → 800명이면 약 530건.
 * 기본 2,000은 그 약 4배라 평소엔 기다리지 않고, 밀림을 따라잡을 때만 천천히 넣는다. 밀림 경보(10만 건·5분)도 울리지 않는다
 * (50만 건 밀림, 유입 530/초여도 (50만-10만)/(2,000-530) ≈ 4.5분).
 *
 * <p>여러 소비자 스레드가 나눠 쓰는 단순한 시간표: 다음 건이 시작할 수 있는 시각을 앞으로 미루고, 그만큼 잔다.
 * 0 이하면 끔. 설정 {@code jejari.behavior.sink-max-per-sec}.
 */
public class SinkPacer {
	private final double nanosPerEvent;
	private long nextFreeAt = Long.MIN_VALUE / 2; // 처음엔 한가한 상태

	public SinkPacer(int maxPerSec) {
		this.nanosPerEvent = maxPerSec > 0 ? 1_000_000_000.0 / maxPerSec : 0;
	}

	/** n건을 처리해도 될 때까지 기다린다 (상한이 꺼져 있으면 바로) */
	public void acquire(int n) throws InterruptedException {
		long waitNanos = reserve(n, System.nanoTime());
		if (waitNanos > 0) {
			Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
		}
	}

	/** 시험용: 지금 시각을 받아 기다릴 시간(나노초)을 돌려준다 */
	synchronized long reserve(int n, long now) {
		if (nanosPerEvent == 0 || n <= 0) {
			return 0;
		}
		// 한가했던 시간은 1초까지만 모아 둔다 (오래 쉬었다고 한꺼번에 몰아 넣지 않게)
		long start = Math.max(nextFreeAt, now - 1_000_000_000L);
		nextFreeAt = start + (long) (n * nanosPerEvent);
		return Math.max(0, start - now);
	}
}
