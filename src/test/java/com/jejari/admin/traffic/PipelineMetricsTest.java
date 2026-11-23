package com.jejari.admin.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class PipelineMetricsTest {

	@Test
	void 밀림은_파티션마다_끝에서_커밋을_뺀_합() {
		TopicPartition p0 = new TopicPartition("jejari.behavior", 0), p1 = new TopicPartition("jejari.behavior", 1),
				p2 = new TopicPartition("jejari.behavior", 2);
		long lag = PipelineMetrics.lag(
				Map.of(p0, new OffsetAndMetadata(100), p1, new OffsetAndMetadata(500), p2, new OffsetAndMetadata(10)),
				Map.of(p0, 130L, p1, 480L));   // p1은 잰 사이에 커밋이 앞섬 → 0, p2는 끝 오프셋 없음 → 뺌
		assertThat(lag).isEqualTo(30);
	}

	@Test
	void 화면에_보일_소비_그룹() {
		assertThat(PipelineMetrics.GROUPS.keySet())
				.containsExactly("behavior-sink", "replay-sink", "seat-release", "notification", "risk-signal", "refund");
	}
}
