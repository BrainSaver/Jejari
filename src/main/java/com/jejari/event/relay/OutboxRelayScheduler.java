package com.jejari.event.relay;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * 릴레이를 주기적으로 돌립니다. 앞 회차가 끝난 뒤 interval-ms만큼 쉬고 다시 돕니다 (fixedDelay라 겹치지 않음).
 * 한 바퀴에서 보낸 게 있으면 쉬지 않고 바로 한 번 더 돌아 적체를 빨리 줄입니다.
 */
public class OutboxRelayScheduler {

	private final OutboxRelay relay;

	public OutboxRelayScheduler(OutboxRelay relay) {
		this.relay = relay;
	}

	@Scheduled(fixedDelayString = "${jejari.event.relay.interval-ms:200}")
	public void run() {
		int rounds = 0;
		while (relay.relayAll() > 0 && ++rounds < 20) {
			// 보낸 게 있으면 바로 다음 바퀴
		}
	}
}
