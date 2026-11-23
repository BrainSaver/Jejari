package com.jejari.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;

import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoClientSettings;

/** 녹화 재생의 서버 기록 조건이 인덱스(회원·기기 + 시간)로 좁혀지는지 */
class ReplayServerFilterTest {

	private static String json(Bson b) {
		BsonDocument d = b.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		return d.toJson();
	}

	@Test
	void 회원과_기기를_알면_둘_다_시간_범위와_함께_OR로() {
		Document s = new Document("_id", "s1").append("member_id", 42L).append("device_hash", "d".repeat(64))
				.append("started_at", new Date(1_000_000_000L)).append("ended_at", new Date(1_000_600_000L));
		String f = json(ReplayAdminService.serverFilter(s, "s1"));
		assertThat(f).contains("\"session_id\": \"s1\"", "\"$or\"", "\"member_id\": 42", "\"device_hash\"", "\"ts\"");
	}

	@Test
	void 회원만_알면_회원과_시간으로() {
		Document s = new Document("_id", "s1").append("member_id", 42L).append("started_at", new Date(1_000_000_000L));
		String f = json(ReplayAdminService.serverFilter(s, "s1"));
		assertThat(f).contains("\"member_id\": 42", "\"ts\"").doesNotContain("$or", "device_hash");
	}

	@Test
	void 아무것도_모르면_예전처럼_session_id만() {
		String f = json(ReplayAdminService.serverFilter(new Document("_id", "s1"), "s1"));
		assertThat(f).isEqualTo("{\"session_id\": \"s1\"}");
	}
}
