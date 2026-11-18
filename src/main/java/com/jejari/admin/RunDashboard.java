package com.jejari.admin;

import java.time.LocalDateTime;

/**
 * ADM-03 실시간 예매 현황 응답 (운행 하나). 10초 폴링 화면 A-04가 그린다 (기능설계서 4.10).
 *
 * @param asOf       이 숫자들을 읽은 시각. 폴링 화면이 "몇 초 전 값인지" 표시하는 데 쓴다
 * @param windowSec  분당 수치를 센 창 길이(초). 60이면 아래 세 값이 그대로 "분당"이다
 */
public record RunDashboard(
		long runId,
		String status,
		LocalDateTime asOf,
		int windowSec,
		Seats seats,
		Rates rates,
		Queue queue,
		Payments payments) {

	/**
	 * 좌석 수. 원천은 {@code seatmap:{runId}} 캐시라 최대 {@code seatmap.cache_ms}만큼 늦을 수 있다.
	 *
	 * @param held 선점 + 대기자 보류 (Redis {@code seat:hold} 키가 살아 있는 좌석)
	 */
	public record Seats(int total, int sold, int held, int remaining) {
	}

	/** 최근 {@code windowSec}초 동안의 건수 */
	public record Rates(int reservations, int payments, int cancels) {
	}

	/**
	 * 대기열 (A 김성원 모듈의 Redis 키를 읽는다).
	 *
	 * @param admitPerSec 초당 입장 수. 대기열이 열려 있지 않으면 0
	 */
	public record Queue(long waiting, long active, long admitPerSec) {
	}

	/**
	 * @param unknown 결과를 모르는 결제 건수. 운행별이 아니라 <b>전체</b> 기준이다 (ADM-09와 같은 수)
	 */
	public record Payments(int unknown) {
	}
}
