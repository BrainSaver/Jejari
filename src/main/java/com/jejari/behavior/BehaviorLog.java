package com.jejari.behavior;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.jejari.auth.AuthPrincipal;

/**
 * 행동 로그 남기기 (RISK-01). 다른 파트는 컨트롤러에서 이 한 줄만 부른다:
 *
 * <pre>
 * BehaviorLog.mark(BehaviorType.SEAT_HOLD, runId, Map.of("seatIds", seatIds, "seatCount", seatIds.size()));
 * </pre>
 *
 * <ul>
 *   <li>주입이 필요 없는 static 메서드다. 다른 파트의 {@code @WebMvcTest}처럼 행동 로그 빈이 없는 곳에서도 그냥 아무 일도 안 한다.</li>
 *   <li>요청에 표시만 하고, 요청이 끝날 때 {@link BehaviorInterceptor}가 응답 코드를 붙여 한 번 보낸다.
 *       실패(409 등)도 보내야 하므로 <b>예외를 던지기 전에</b> 부른다.</li>
 *   <li>회원·세션·IP·기기·UA·시각은 자동으로 채운다. meta에는 ID·숫자·코드만 넣는다 (이름·번호·이메일 금지).
 *       허용하지 않는 값은 보내기 전에 걸러진다 ({@link BehaviorMeta}).</li>
 *   <li>한 요청에서 여러 번 부르면 마지막 것만 남는다.</li>
 * </ul>
 */
public final class BehaviorLog {

	static final String ATTR = BehaviorLog.class.getName() + ".pending";

	private BehaviorLog() {
	}

	public static void mark(BehaviorType type, Long runId, Map<String, ?> meta) {
		RequestAttributes req = RequestContextHolder.getRequestAttributes();
		if (req == null || type == null) {
			return; // 웹 요청 밖(배치·테스트)에서 불리면 무시
		}
		req.setAttribute(ATTR, new Pending(type, runId, meta == null ? Map.of() : meta, principal()),
				RequestAttributes.SCOPE_REQUEST);
	}

	public static void mark(BehaviorType type, Long runId) {
		mark(type, runId, Map.of());
	}

	private static AuthPrincipal principal() {
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		return a != null && a.getPrincipal() instanceof AuthPrincipal p ? p : null;
	}

	/** 컨트롤러 안에서 표시한 내용. 인증 정보는 이때 잡아 둔다 (요청 끝에는 보안 컨텍스트가 비어 있을 수 있음) */
	record Pending(BehaviorType type, Long runId, Map<String, ?> meta, AuthPrincipal principal) {
	}
}
