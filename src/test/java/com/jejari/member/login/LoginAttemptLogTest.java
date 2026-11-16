package com.jejari.member.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.jejari.member.login.LoginAttemptLog.Result;
import com.jejari.support.TestAuthPolicy;

/** 로그인 기록은 로그인 요청을 기다리게 하지 않는다 (MongoDB가 느리거나 죽어도). */
class LoginAttemptLogTest {

	final MongoTemplate mongo = mock(MongoTemplate.class);
	final CountDownLatch release = new CountDownLatch(1);
	LoginAttemptLog log;

	LoginAttemptLog newLog() {
		log = new LoginAttemptLog(mongo, TestAuthPolicy.defaults(), Clock.systemUTC());
		return log;
	}

	@AfterEach
	void tearDown() throws Exception {
		release.countDown();
		log.destroy();
	}

	void mongoHangs() {
		doAnswer(inv -> {
			release.await(30, TimeUnit.SECONDS); // 연결이 안 되는 MongoDB (예전 기본 대기 30초)
			return null;
		}).when(mongo).insert(any(Document.class), anyString());
	}

	@Test
	void Mongo가_멈춰_있어도_바로_돌아온다() {
		mongoHangs();
		LoginAttemptLog l = newLog();
		long t = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			l.record(1L, "e", Result.SUCCESS, "ip", "ua", "s", 10L);
		}
		assertThat((System.nanoTime() - t) / 1_000_000).as("100건 기록 요청 ms").isLessThan(500);
	}

	@Test
	void 저장_실패해도_예외가_나오지_않는다() {
		doThrow(new RuntimeException("Mongo down")).when(mongo).insert(any(Document.class), anyString());
		LoginAttemptLog l = newLog();
		assertThatCode(() -> l.record(1L, "e", Result.WRONG_PASSWORD, "ip", null, null, 5L)).doesNotThrowAnyException();
		verify(mongo, timeout(2000)).insert(any(Document.class), anyString());
	}

	@Test
	void 큐가_가득_차면_버리고_세며_막히지_않는다() {
		mongoHangs();
		LoginAttemptLog l = newLog();
		long t = System.nanoTime();
		// 1건은 저장 중(멈춤), QUEUE_CAPACITY건은 대기, 나머지 5건은 버려진다
		for (int i = 0; i < LoginAttemptLog.QUEUE_CAPACITY + 6; i++) {
			l.record(1L, "e", Result.NOT_FOUND, "ip", null, null, null);
		}
		assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(3000);
		assertThat(l.droppedCount()).isEqualTo(5);
	}

	@Test
	void 시각은_시도한_순간_보관_만료와_해시만_담긴다() {
		LoginAttemptLog l = newLog();
		long before = System.currentTimeMillis();
		l.record(7L, "emailhash", Result.SUCCESS, "iphash", "ua", "sess", 42L);
		ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
		verify(mongo, timeout(2000)).insert(doc.capture(), anyString());
		Document d = doc.getValue();
		assertThat(d.getDate("ts").getTime()).isBetween(before, System.currentTimeMillis());
		assertThat(d.getDate("expire_at").getTime() - d.getDate("ts").getTime())
				.isEqualTo(TimeUnit.DAYS.toMillis(30));
		assertThat(d.getString("email_hash")).isEqualTo("emailhash");
		assertThat(d.getString("result")).isEqualTo("SUCCESS");
	}
}
