package com.jejari.replay.web;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.web.ClientIpResolver;
import com.jejari.replay.ReplayBatch;
import com.jejari.replay.ReplayService;
import com.jejari.replay.ReplayService.Config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ADM-11 녹화 수신 (회원 화면의 녹화기가 부른다).
 *
 * <pre>
 * GET  /api/v1/replay/config   → {enabled, mode: FULL/SAMPLED/OFF, batchSec, maxPathPoints}
 * POST /api/v1/replay/events   {"events":[{seq, ts, type, screen, target, x, y, path, inputLen, runId}, …]} → 202 {accepted}
 * </pre>
 * 세션은 로그인 세션(액세스 토큰의 sid)과 1:1 이라 클라이언트가 고를 수 없다. 기기·IP는 해시만.
 */
@RestController
@RequestMapping("/api/v1/replay")
public class ReplayController {

	public record EventsRequest(List<ReplayBatch.In> events) {
	}

	static final String DEVICE_HEADER = "X-Device-Id";
	private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_\\-]{8,128}$");

	private final ReplayService service;
	private final ClientIpResolver ipResolver;
	private final ObjectProvider<Pseudonymizer> pseudonymizer;

	public ReplayController(ReplayService service, ClientIpResolver ipResolver,
			ObjectProvider<Pseudonymizer> pseudonymizer) {
		this.service = service;
		this.ipResolver = ipResolver;
		this.pseudonymizer = pseudonymizer;
	}

	@GetMapping("/config")
	public Config config(@AuthenticationPrincipal AuthPrincipal me) {
		return service.config(me.memberId(), me.sessionId());
	}

	@PostMapping("/events")
	public ResponseEntity<Map<String, Integer>> events(@AuthenticationPrincipal AuthPrincipal me,
			@RequestBody(required = false) EventsRequest body, HttpServletRequest req) {
		Pseudonymizer hash = pseudonymizer.getIfAvailable();
		String device = req.getHeader(DEVICE_HEADER);
		String deviceHash = hash != null && device != null && DEVICE_ID.matcher(device).matches() ? hash.device(device)
				: null;
		String ipHash = hash == null ? null : hash.ip(ipResolver.resolve(req));
		int n = service.accept(me.memberId(), me.sessionId(), deviceHash, ipHash, body == null ? null : body.events());
		return ResponseEntity.accepted().body(Map.of("accepted", n));
	}
}
