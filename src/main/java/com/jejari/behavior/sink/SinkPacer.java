package com.jejari.behavior.sink;

/**
 * 행동 로그 적재 속도 상한 (소비자 스레드 전체 합). 밀린 로그를 따라잡을 때 최대 속도로 쏟아 넣지 않게 한다.
 *
 * <p><b>왜</b> (2026-11-20 실측, 4코어에 앱·Oracle·MongoDB·Kafka 같이): 적재는 최대 초당 약 2만~2만8천 건까지 되는데,
 * 그 속도로 밀린 50만 건을 넣는 동안 CPU가 99%가 되어 같은 시각 예매의 좌석 선점 p95가 67~76ms → 164~364ms로 느려졌다.
 * DB 커넥션 대기는 0이라 원인은 커넥션 풀이 아니라 CPU였다. 밀림은 장애(MongoDB·Kafka) 직후나 디스크 버퍼를 다시 보낼 때
 * 생기고, 그때가 판매 오픈과 겹치면 예매가 느려진다.
 *
 * <p>평소 유입(실측 용량 시험 800명에서 요청 초당 약 740건)은 상한보다 한참 아래라 영향이 없다. 상한은 밀림을 따라잡는
 * 속도만 정한다: 5,000이면 50만 건 밀림을 약 100초에 따라잡는다 (밀림 경보 기준 10만 건·5분보다 짧음).
 *
 * <p>여러 소비자 스레드가 나눠 쓰는 단순한 시간표: 다음 건이 시작할 수 있는 시각을 앞으로 미루고, 그만큼 잔다.
 * 0 이하면 끔.
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
