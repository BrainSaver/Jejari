package com.jejari.admin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ADM-03 분당 이력. 화면 A-04의 "분당 예약·결제·취소" 그래프가 처음부터 채워져 보이게 한다.
 *
 * <p>{@link RunDashboard#rates()}는 "지금부터 60초 전까지" 한 묶음이라 10초마다 받으면 창이 겹친다.
 * 이력은 겹치지 않게 <b>분 경계</b>로 자른다 (10:31:00 ~ 10:31:59 = 10:31 한 칸).
 *
 * @param asOf    읽은 시각 (Oracle 기준 분, 마지막 칸의 시작)
 * @param minutes 오래된 것부터. 건수가 0인 분도 빠짐없이 들어 있다. 마지막 칸은 아직 진행 중인 분이다
 */
public record RateHistory(long runId, LocalDateTime asOf, List<Minute> minutes) {

	/** 그 분(minute) 동안 생긴 예약·승인된 결제·취소 건수. 세는 기준은 {@link RunDashboard.Rates}와 같다 */
	public record Minute(LocalDateTime minute, int reservations, int payments, int cancels) {
	}
}
