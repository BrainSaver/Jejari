package com.jejari.queue.domain;

import com.jejari.queue.port.OnSaleRunProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1초에 한 번 입장 처리를 한다. 처리하는 것은 활성 운행 목록(queue:runs)의 운행뿐이라, 판매 중인 운행이 1,000건이어도
 * 줄 선 사람이 없으면 Redis 명령은 1초에 한 번 SMEMBERS뿐이다. 한 운행이 실패해도 다른 운행은 계속한다.
 * 앱이 여러 대여도 입장 속도가 정책값이 되는 것은 QueueAdmitter(admit.lua)가 보장한다.
 */
public class QueueAdmitScheduler {
	private static final Logger log = LoggerFactory.getLogger(QueueAdmitScheduler.class);

	private final OnSaleRunProvider runs;
	private final QueueAdmitter admitter;

	public QueueAdmitScheduler(OnSaleRunProvider runs, QueueAdmitter admitter) {
		this.runs = runs;
		this.admitter = admitter;
	}

	@Scheduled(fixedRate = 1000)
	public void tick() {
		try {
			admitter.admitActiveRuns();
		} catch (RuntimeException e) {
			log.error("입장 처리 실패", e);
		}
	}

	/** 앱이 뜰 때와 5분마다: 활성 운행 목록에서 빠진 운행(목록 도입 전에 줄 선 사람 등)을 다시 넣는다. */
	@Scheduled(initialDelay = 0, fixedRate = 300_000)
	public void reconcile() {
		try {
			admitter.reconcile(runs.onSaleRunIds());
		} catch (RuntimeException e) {
			log.error("활성 운행 목록 맞추기 실패", e);
		}
	}
}
