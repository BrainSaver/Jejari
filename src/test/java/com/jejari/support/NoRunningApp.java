package com.jejari.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 통합 테스트를 시작하기 전에 <b>같은 Oracle·Redis·Kafka를 보는 앱이 따로 떠 있지 않은지</b> 확인한다.
 *
 * <p>앱이 떠 있으면 테스트가 조용히, 그리고 실행할 때마다 다르게 깨진다. 겪은 것:
 * <ul>
 *   <li>앱의 {@code HoldExpiryScanner}가 테스트가 만든 HOLD를 <b>먼저 만료시킨다</b> →
 *       "기한 초과 반환율 20건 중 20건"이 13건·10건·6건처럼 매번 다른 수로 실패</li>
 *   <li>앱의 outbox 릴레이가 테스트 행을 먼저 가져간다 → {@code OutboxCommitInfraTest}가
 *       "보낸 것이 없다"로 실패 (길동현 씨 기능 문서에도 적혀 있다)</li>
 *   <li>앱이 같은 Kafka 소비 그룹을 나눠 쓴다 → 행동 로그 테스트가 끝나지 않음 (강다인 씨 트러블슈팅)</li>
 * </ul>
 *
 * <p>증상만 보면 원인을 짐작하기 어렵다. 그래서 <b>시작하자마자 한 줄로 알려 주고 멈춘다.</b>
 *
 * <p>일부러 앱을 띄운 채 돌려야 한다면 {@code -Djejari.test.allow-running-app=true}.
 * 포트가 다르면 {@code -Djejari.test.app-port=9090}.
 */
public class NoRunningApp implements BeforeAllCallback {

	private static final String ALLOW = "jejari.test.allow-running-app";
	private static final String PORT = "jejari.test.app-port";

	/** 한 번만 확인한다. 테스트 클래스마다 찌르면 느려진다 */
	private static volatile Boolean checked;

	@Override
	public void beforeAll(ExtensionContext context) {
		if (Boolean.getBoolean(ALLOW) || Boolean.TRUE.equals(checked)) {
			return;
		}
		int port = Integer.getInteger(PORT, 8080);
		if (running(port)) {
			throw new IllegalStateException("""

					====================================================================
					 앱이 %d 포트에 떠 있습니다. 통합 테스트를 그대로 돌리면 조용히 깨집니다.

					 앱의 만료 스캐너가 테스트의 좌석 선점을 먼저 만료시키고,
					 outbox 릴레이가 테스트 이벤트를 먼저 가져가고,
					 같은 Kafka 소비 그룹을 나눠 씁니다. 실행할 때마다 다른 테스트가 실패합니다.

					 bootRun 창에서 Ctrl+C로 앱을 끄고 다시 돌리세요.
					 (확인: netstat -ano | grep :%d)

					 일부러 띄운 채 돌려야 하면 -D%s=true
					====================================================================
					""".formatted(port, port, ALLOW));
		}
		checked = true;
	}

	private static boolean running(int port) {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build()) {
			HttpResponse<String> res = client.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
							.timeout(Duration.ofMillis(700)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			// 우리 앱인지까지 본다. 다른 프로그램이 그 포트를 쓰고 있을 수도 있다
			return res.statusCode() == 200 && res.body().contains("status");
		} catch (Exception e) {
			return false;   // 안 떠 있음 (또는 확인할 수 없음) → 그냥 진행한다
		}
	}
}
