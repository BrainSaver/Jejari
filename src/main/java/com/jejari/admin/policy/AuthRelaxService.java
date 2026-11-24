package com.jejari.admin.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.jejari.admin.policy.PolicyAdminService.Policy;
import com.jejari.admin.policy.PolicyAdminService.UpdateCommand;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 인증 시도 제한 임시 해제 (관리자 화면 "운영 설정"). 설계: docs/강동균/설계안-인증-시도제한-임시해제.md
 *
 * <pre>
 * 시작: IP(비우면 지금 접속한 IP) + 몇 시간(1~24) + 사유 → ip_hash로 바꿔 auth.rate_relax_ip_hashes,
 *       사유 auth.rate_relax_reason, 마지막에 종료 시각 auth.rate_relax_until (마지막에 넣어야 덜 채운 상태로 켜지지 않음)
 * 끝:   종료 시각 OFF, IP NONE
 * </pre>
 *
 * <p>값은 운영 설정과 같은 길({@link PolicyAdminService#update})로 저장하므로 범위 검사·버전·정책 이력(누가·언제·무엇을)·
 * Redis 반영이 그대로 따라온다. 원본 IP는 어디에도 저장하지 않는다.
 *
 * <p>자동 복구는 여기서 하지 않는다. {@link AuthPolicy#limitFor}가 읽을 때마다 종료 시각과 비교하므로, "끝" 버튼을
 * 안 눌러도 시각이 지나면 평소 한도다. 화면에 남은 OFF 아닌 종료 시각은 지난 기록일 뿐이다.
 */
@Service
@MemberAuthEnabled
public class AuthRelaxService {

	/** {@code ClientIpResolver}와 같은 IP 문자 집합 */
	private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
	static final int MIN_REASON = 5;

	private final PolicyAdminService policies;
	private final AuthPolicy auth;
	private final Pseudonymizer pseudonymizer;
	private final Clock clock;

	public AuthRelaxService(PolicyAdminService policies, AuthPolicy auth, Pseudonymizer pseudonymizer, Clock clock,
			MeterRegistry meters) {
		this.policies = policies;
		this.auth = auth;
		this.pseudonymizer = pseudonymizer;
		this.clock = clock;
		// 해제 중이면 1. Grafana가 24시간 넘게 1이면 경보 (상한 검사가 뚫렸을 때 대비)
		Gauge.builder("jejari.auth.rate_relax_active", auth, a -> a.relaxActive() ? 1 : 0)
				.description("인증 시도 제한 임시 해제 중 (1)").register(meters);
	}

	/**
	 * @param active        지금 해제 중
	 * @param until         종료 시각 (해제 중이 아니면 null)
	 * @param remainingMin  남은 분
	 * @param ipCount       해제한 IP 수
	 * @param includesMe    지금 화면을 연 관리자 IP가 들어 있나
	 */
	public record Status(boolean active, LocalDateTime until, long remainingMin, int ipCount, boolean includesMe,
			String reason, long signupPerHour, long loginPerMin, long normalSignupPerHour, long normalLoginPerMin,
			long maxHours) {
	}

	/**
	 * @param signupPerHour 해제 동안 가입 한도 (비우면 지금 값 그대로). 부하 시험 칸이 게이지 인원에 맞춰 보낸다
	 * @param loginPerMin   해제 동안 로그인 한도 (비우면 그대로)
	 */
	public record StartCommand(String ip, Integer hours, String reason, Integer signupPerHour, Integer loginPerMin) {
	}

	public Status status(String requesterIp) {
		boolean active = auth.relaxActive();
		LocalDateTime until = active ? auth.relaxUntil() : null;
		Set<String> ips = active ? auth.relaxIpHashes() : Set.of();
		long remaining = until == null ? 0 : Math.max(0, Duration.between(LocalDateTime.now(clock), until).toMinutes());
		boolean me = requesterIp != null && ips.contains(pseudonymizer.ip(requesterIp));
		String reason = active ? valueOrNull(AuthPolicy.RATE_RELAX_REASON) : null;
		return new Status(active, until, remaining, ips.size(), me, reason,
				auth.number(AuthPolicy.RATE_RELAX_SIGNUP_PER_HOUR), auth.number(AuthPolicy.RATE_RELAX_LOGIN_PER_MIN),
				auth.number(AuthPolicy.SIGNUP_RATE_PER_HOUR), auth.number(AuthPolicy.LOGIN_RATE_PER_MIN),
				AuthPolicy.RATE_RELAX_MAX_HOURS);
	}

	/**
	 * 해제 시작(또는 IP 추가·연장). 이미 해제 중이면 IP를 더하고(최대 3개, 넘으면 가장 먼저 넣은 것부터 뺌) 종료 시각을 새로 정한다.
	 *
	 * @param requesterIp 지금 접속한 관리자의 IP ({@code ip}를 비우면 이 IP를 푼다 — k6를 같은 PC에서 돌리는 경우)
	 */
	public Status start(long adminId, StartCommand c, String requesterIp) {
		String ip = c.ip() == null || c.ip().isBlank() ? requesterIp : c.ip().strip();
		if (ip == null || !IP.matcher(ip).matches()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "IP 주소 형식이 맞지 않습니다.");
		}
		int hours = c.hours() == null ? 0 : c.hours();
		if (hours < 1 || hours > AuthPolicy.RATE_RELAX_MAX_HOURS) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
					"해제 시간은 1~" + AuthPolicy.RATE_RELAX_MAX_HOURS + "시간입니다.");
		}
		String reason = c.reason() == null ? "" : c.reason().strip();
		if (reason.length() < MIN_REASON) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "사유를 " + MIN_REASON + "자 이상 적어 주세요.");
		}

		List<String> hashes = new ArrayList<>(auth.relaxActive() ? auth.relaxIpHashes() : Set.of());
		String mine = pseudonymizer.ip(ip);
		hashes.remove(mine);
		hashes.add(mine);
		while (hashes.size() > AuthPolicy.RATE_RELAX_MAX_IPS) {
			hashes.remove(0);
		}
		// 분 단위로 내림 → 저장 검사(지금 + 24시간 이내)를 항상 통과
		LocalDateTime until = LocalDateTime.now(clock).plusHours(hours).withSecond(0).withNano(0);

		// 한도는 운영 설정과 같은 범위 검사(가입 1~5000, 로그인 1~10000)를 거친다
		if (c.signupPerHour() != null) {
			set(adminId, AuthPolicy.RATE_RELAX_SIGNUP_PER_HOUR, Integer.toString(c.signupPerHour()));
		}
		if (c.loginPerMin() != null) {
			set(adminId, AuthPolicy.RATE_RELAX_LOGIN_PER_MIN, Integer.toString(c.loginPerMin()));
		}
		set(adminId, AuthPolicy.RATE_RELAX_IP_HASHES, String.join(",", new LinkedHashSet<>(hashes)));
		set(adminId, AuthPolicy.RATE_RELAX_REASON, reason);
		set(adminId, AuthPolicy.RATE_RELAX_UNTIL, until.format(AuthPolicy.RELAX_TIME)); // 마지막에 켠다
		return status(requesterIp);
	}

	/** 지금 끝내기. 종료 시각을 먼저 끈다 (그 순간부터 평소 한도) */
	public Status stop(long adminId, String requesterIp) {
		set(adminId, AuthPolicy.RATE_RELAX_UNTIL, AuthPolicy.RELAX_OFF);
		set(adminId, AuthPolicy.RATE_RELAX_IP_HASHES, AuthPolicy.RELAX_NO_IPS);
		return status(requesterIp);
	}

	private void set(long adminId, String key, String value) {
		Policy cur = policies.get(key); // ERD v39 행이 없으면 NOT_FOUND (서버에 db/patch/v39.sql을 넣어야 함)
		if (value.equals(cur.value())) {
			return;
		}
		policies.update(adminId, key, new UpdateCommand(value, cur.version()));
	}

	private String valueOrNull(String key) {
		try {
			return policies.get(key).value();
		} catch (BusinessException e) {
			return null;
		}
	}
}
