package com.jejari.behavior;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthPrincipal;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 시작 시각을 적고, 요청이 끝날 때 {@link BehaviorLog#mark}로 표시된 요청만 이벤트로 만들어 보낸다.
 *
 * <ul>
 *   <li>시각은 요청을 받은 순간 (응답 시각이 아님). 클릭 간격 분석 기준</li>
 *   <li>응답 코드는 예외 처리(GlobalExceptionHandler)까지 끝난 값 → 409로 끝난 선점도 409로 남는다</li>
 *   <li>IP·기기 ID는 해시만. 기기 ID 헤더({@code X-Device-Id})는 클라이언트 값이라 형식을 검사하고, 이상하면 버린다</li>
 *   <li>이벤트를 만들다 무슨 일이 생겨도 응답에는 영향이 없다 (afterCompletion은 응답이 나간 뒤 실행)</li>
 * </ul>
 */
public class BehaviorInterceptor implements HandlerInterceptor {
	private static final Logger log = LoggerFactory.getLogger(BehaviorInterceptor.class);

	static final String START_ATTR = BehaviorInterceptor.class.getName() + ".start";
	static final String DEVICE_HEADER = "X-Device-Id";
	static final String CHANNEL_HEADER = "X-Channel";
	private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_\\-]{8,128}$");
	private static final int USER_AGENT_MAX = 300;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final ObjectProvider<BehaviorPublisher> publisher;
	private final ObjectProvider<Pseudonymizer> pseudonymizer;
	private final ClientIpResolver ipResolver;
	private final Clock clock;

	public BehaviorInterceptor(ObjectProvider<BehaviorPublisher> publisher, ObjectProvider<Pseudonymizer> pseudonymizer,
			ClientIpResolver ipResolver, Clock clock) {
		this.publisher = publisher;
		this.pseudonymizer = pseudonymizer;
		this.ipResolver = ipResolver;
		this.clock = clock;
	}

	@Override
	public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
		req.setAttribute(START_ATTR, clock.instant());
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
		if (!(req.getAttribute(BehaviorLog.ATTR) instanceof BehaviorLog.Pending p)) {
			return;
		}
		BehaviorPublisher pub = publisher.getIfAvailable();
		Pseudonymizer hash = pseudonymizer.getIfAvailable();
		if (pub == null || hash == null) {
			return; // 행동 로그 꺼짐 (키 없음 등)
		}
		try {
			pub.publish(build(req, res, p, hash));
		} catch (RuntimeException e) {
			log.warn("행동 로그 이벤트를 만들지 못함 type={}: {}", p.type(), e.toString());
		}
	}

	private BehaviorEvent build(HttpServletRequest req, HttpServletResponse res, BehaviorLog.Pending p,
			Pseudonymizer hash) {
		Instant start = req.getAttribute(START_ATTR) instanceof Instant i ? i : clock.instant();
		AuthPrincipal who = p.principal();
		boolean member = who != null && who.actorType() == ActorType.MEMBER;
		BehaviorMeta.Result meta = BehaviorMeta.sanitize(p.meta());
		if (meta.dropped() > 0) {
			log.warn("행동 로그 meta에서 허용하지 않는 값 {}개를 뺌 type={} (ID·숫자·코드만 넣어 주세요)", meta.dropped(), p.type());
		}
		return new BehaviorEvent(
				UUID.randomUUID().toString(),
				p.type().name(),
				start.atZone(SEOUL).toOffsetDateTime().toString(),
				member ? who.actorId() : null,
				who == null ? null : who.sessionId(),
				deviceHash(req, hash),
				hash.ip(ipResolver.resolve(req)),
				p.runId(),
				"APP".equals(req.getHeader(CHANNEL_HEADER)) ? "APP" : "WEB",
				userAgent(req),
				res.getStatus(),
				meta.meta(),
				Math.max(0, java.time.Duration.between(start, clock.instant()).toMillis()));
	}

	private static String deviceHash(HttpServletRequest req, Pseudonymizer hash) {
		String d = req.getHeader(DEVICE_HEADER);
		return d != null && DEVICE_ID.matcher(d).matches() ? hash.device(d) : null;
	}

	private static String userAgent(HttpServletRequest req) {
		String ua = req.getHeader(HttpHeaders.USER_AGENT);
		if (ua == null) {
			return null;
		}
		String clean = ua.replaceAll("\\p{Cntrl}", "");
		return clean.length() > USER_AGENT_MAX ? clean.substring(0, USER_AGENT_MAX) : clean;
	}
}
