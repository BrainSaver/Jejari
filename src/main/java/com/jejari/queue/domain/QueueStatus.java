package com.jejari.queue.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** 번호표. 대기 중이면 rank·ahead·estimatedWaitSec, 입장했으면 passToken·passExpiresAt이 채워진다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatus(
		State state,
		long runId,
		long ticketNo,
		Long rank,
		Long ahead,
		Long estimatedWaitSec,
		int pollIntervalSec,
		String passToken,
		OffsetDateTime passExpiresAt) {

	public enum State { WAITING, ADMITTED }
}
