package com.jejari.chat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.chat.ChatService.Turn;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.member.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 예매 안내 챗봇 API. 비회원도 쓸 수 있어(SecurityConfig) Groq 호출량을 IP별로 제한한다:
 * 1분에 {@value #PER_MINUTE}번, 하루에 {@value #PER_DAY}번. 하루 한도는 챗봇을 잔여석 감시용 통로로 쓰지 못하게 하려는 것이다
 * (챗봇 조회는 행동 로그·좌석 조회 속도 제한을 거치지 않으므로). 앱 1대 기준 메모리 계수라 재시작하면 초기화된다.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

	static final int PER_MINUTE = 10;
	static final int PER_DAY = 100;
	private static final long DAY_MS = 86_400_000L;
	private static final int MAX_TRACKED_IPS = 10_000;

	public record ChatRequest(List<Turn> messages) {
	}

	public record ChatResponse(String reply, List<ChatService.Action> actions) {
	}

	private final ChatService chat;
	private final ClientIpResolver ips;
	private final Map<String, long[]> windows = new ConcurrentHashMap<>(); // ip → {분 시작 ms, 횟수}
	private final Map<String, long[]> days = new ConcurrentHashMap<>(); // ip → {하루 시작 ms, 횟수}

	private final Clock clock;

	public ChatController(ChatService chat, ClientIpResolver ips, Clock clock) {
		this.chat = chat;
		this.ips = ips;
		this.clock = clock;
	}

	/** 화면이 챗봇 버튼을 보일지 정한다 */
	@GetMapping("/status")
	public Map<String, Boolean> status() {
		return Map.of("enabled", chat.enabled());
	}

	@PostMapping
	public ChatResponse ask(@RequestBody ChatRequest req, HttpServletRequest http) {
		if (!chat.enabled()) {
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "죄송해요, 지금은 예매 도우미가 쉬고 있어요. 열차는 '검색' 화면에서 찾아 주세요.");
		}
		allow(ips.resolve(http));
		ChatService.Answer a = chat.reply(req == null ? null : req.messages());
		return new ChatResponse(a.text(), a.actions());
	}

	void allow(String ip) {
		long now = clock.millis();
		if (count(windows, ip, now, 60_000) > PER_MINUTE) {
			throw new BusinessException(CommonErrorCode.RATE_LIMITED, "질문이 조금 빨랐어요. 잠깐 숨 돌리시고 1분쯤 뒤에 다시 물어봐 주세요.");
		}
		if (count(days, ip, now, DAY_MS) > PER_DAY) {
			throw new BusinessException(CommonErrorCode.RATE_LIMITED, "죄송해요, 오늘 물어보실 수 있는 횟수를 다 쓰셨어요. 열차는 '검색' 화면에서 바로 찾으실 수 있어요.");
		}
	}

	private static long count(Map<String, long[]> map, String ip, long now, long windowMs) {
		if (map.size() > MAX_TRACKED_IPS) {
			map.entrySet().removeIf(e -> now - e.getValue()[0] > windowMs);
		}
		return map.compute(ip, (k, cur) -> {
			if (cur == null || now - cur[0] >= windowMs) {
				return new long[] { now, 1 };
			}
			cur[1]++;
			return cur;
		})[1];
	}
}
