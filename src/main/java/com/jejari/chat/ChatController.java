package com.jejari.chat;

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
 * 1분에 {@value #PER_MINUTE}번. 앱 1대 기준 메모리 계수라 재시작하면 초기화된다.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

	static final int PER_MINUTE = 10;
	private static final int MAX_TRACKED_IPS = 10_000;

	public record ChatRequest(List<Turn> messages) {
	}

	public record ChatResponse(String reply) {
	}

	private final ChatService chat;
	private final ClientIpResolver ips;
	private final Map<String, long[]> windows = new ConcurrentHashMap<>(); // ip → {분 시작 ms, 횟수}

	public ChatController(ChatService chat, ClientIpResolver ips) {
		this.chat = chat;
		this.ips = ips;
	}

	/** 화면이 챗봇 버튼을 보일지 정한다 */
	@GetMapping("/status")
	public Map<String, Boolean> status() {
		return Map.of("enabled", chat.enabled());
	}

	@PostMapping
	public ChatResponse ask(@RequestBody ChatRequest req, HttpServletRequest http) {
		if (!chat.enabled()) {
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "챗봇이 아직 켜져 있지 않습니다.");
		}
		allow(ips.resolve(http));
		return new ChatResponse(chat.reply(req == null ? null : req.messages()));
	}

	private void allow(String ip) {
		long now = System.currentTimeMillis();
		if (windows.size() > MAX_TRACKED_IPS) {
			windows.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
		}
		long[] w = windows.compute(ip, (k, cur) -> {
			if (cur == null || now - cur[0] >= 60_000) {
				return new long[] { now, 1 };
			}
			cur[1]++;
			return cur;
		});
		if (w[1] > PER_MINUTE) {
			throw new BusinessException(CommonErrorCode.RATE_LIMITED, "질문이 너무 빨라요. 잠시 후 다시 물어봐 주세요.");
		}
	}
}
