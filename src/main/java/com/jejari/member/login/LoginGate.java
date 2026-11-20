package com.jejari.member.login;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.RetryAfterException;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 로그인 대기실 (벌크헤드). 회원 로그인을 요청 스레드(Tomcat)가 아니라 <b>전용 일꾼 N명</b>이 처리하고, 나머지는 줄을 세운다.
 *
 * <p>왜: 비밀번호 확인(Argon2id)은 일부러 느리고 동시에 {@code auth.max_concurrent_hash}개(서버 4개)만 계산한다. 예전에는 자리를
 * 못 받은 로그인이 <b>Tomcat 요청 스레드를 붙잡은 채</b> 최대 3초 기다려서, 200명이 한꺼번에 로그인하면 스레드 200개가 다 차고
 * 로그인과 상관없는 대기열 폴링·좌석 조회까지 멈췄다 (해시 4개로 로컬 실측: 동시 200명에서 초당 10건, 5xx 24%,
 * docs/강동균/용량-시험-안내.md). 지금은
 * <ul>
 *   <li>일꾼 수 = 해시 자리 수. 일꾼이 해시 자리를 기다릴 일이 없고, 로그인이 아무리 몰려도 CPU를 해시 N개 이상 쓰지 않는다</li>
 *   <li>요청 스레드는 줄에 넣자마자 풀려나 다른 API를 처리한다 (컨트롤러가 CompletableFuture를 돌려줌 — 서블릿 비동기)</li>
 *   <li>줄은 {@code jejari.auth.login-queue-capacity}(기본 200)까지. 넘거나, 줄에서 {@code login-queue-wait-ms}(기본 8초)를 넘게
 *       기다린 요청은 해시를 계산하지 않고 429 {@code AUTH_LOGIN_BUSY} + {@code Retry-After}(예상 대기 초)로 돌려보낸다.
 *       화면은 그만큼 뒤 자동으로 다시 보낸다. 느린 503 대신 빠른 "잠시 뒤"</li>
 * </ul>
 * 관리자 로그인·가입은 지나가지 않는다 (몰리지 않음). 지표: {@code jejari_login_queue_size}, {@code jejari_login_busy_total}.
 */
@Component
@MemberAuthEnabled
public class LoginGate implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(LoginGate.class);
	static final long MAX_RETRY_AFTER_SEC = 30;

	private final ThreadPoolExecutor workers;
	private final int threads;
	private final long maxWaitMs;
	private final AtomicLong busy = new AtomicLong();
	/** 최근 로그인 1건 처리 시간(ms)의 지수 이동 평균 — Retry-After 계산용 */
	private volatile double avgMs = 100;

	public LoginGate(AuthPolicy policy, MeterRegistry meters,
			@Value("${jejari.auth.login-queue-capacity:200}") int capacity,
			@Value("${jejari.auth.login-queue-wait-ms:8000}") long maxWaitMs) {
		this.threads = (int) Math.max(1, policy.number(AuthPolicy.MAX_CONCURRENT_HASH));
		this.maxWaitMs = maxWaitMs;
		this.workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(Math.max(1, capacity)), r -> {
					Thread t = new Thread(r, "login-gate");
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.AbortPolicy());
		Gauge.builder("jejari.login.queue.size", workers, w -> w.getQueue().size())
				.description("로그인 대기실에서 기다리는 요청 수").register(meters);
		FunctionCounter.builder("jejari.login.busy", busy, AtomicLong::get)
				.description("로그인 대기실이 가득 차거나 너무 오래 기다려 429로 돌려보낸 수").register(meters);
		log.info("로그인 대기실: 일꾼 {}명 (= 해시 자리), 줄 {}개, 최대 대기 {}ms", threads, capacity, maxWaitMs);
	}

	/** 줄에 넣고 바로 돌아온다. 줄이 가득 차면 즉시 {@link RetryAfterException} */
	public <T> CompletableFuture<T> submit(Supplier<T> work) {
		CompletableFuture<T> out = new CompletableFuture<>();
		long enqueued = System.nanoTime();
		Map<String, String> mdc = MDC.getCopyOfContextMap();    // traceId를 일꾼 스레드로
		try {
			workers.execute(() -> {
				if (mdc != null) {
					MDC.setContextMap(mdc);
				}
				try {
					long waited = (System.nanoTime() - enqueued) / 1_000_000;
					if (waited > maxWaitMs) {               // 너무 오래 기다림: 해시를 계산하지 않고 돌려보냄 (클라이언트도 지쳤을 시간)
						out.completeExceptionally(rejected());
						return;
					}
					long started = System.nanoTime();
					T v = work.get();
					avgMs = avgMs * 0.9 + (System.nanoTime() - started) / 1_000_000.0 * 0.1;
					out.complete(v);
				} catch (Throwable e) {
					out.completeExceptionally(e);
				} finally {
					MDC.clear();
				}
			});
		} catch (RejectedExecutionException e) {
			throw rejected();
		}
		return out;
	}

	private RetryAfterException rejected() {
		busy.incrementAndGet();
		return new RetryAfterException(AuthErrorCode.AUTH_LOGIN_BUSY, retryAfterSec());
	}

	/** 지금 줄이 다 빠지는 데 걸릴 예상 시간 (초, 1~30) */
	long retryAfterSec() {
		double sec = (workers.getQueue().size() + threads) * avgMs / threads / 1000.0;
		return Math.min(MAX_RETRY_AFTER_SEC, Math.max(1, (long) Math.ceil(sec)));
	}

	int queueSize() {
		return workers.getQueue().size();
	}

	@Override
	public void destroy() {
		workers.shutdown();
	}
}
