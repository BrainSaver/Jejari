package com.jejari.common.pii;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 개인정보 암호화(AES-256-GCM) 빈. 키({@code JEJARI_ENC_KEYS}, {@code JEJARI_ENC_ACTIVE})가 설정된 곳에서만 만들어집니다.
 * HMAC 키({@code JEJARI_HMAC_KEY})도 함께 넘겨 두 키가 같으면 기동을 거부합니다 (키 분리).
 * 키가 잘못되면(32바이트 아님, 약한 키 등) 앱이 뜨지 않습니다.
 */
@Configuration
public class PiiConfig {

	@Bean
	@ConditionalOnExpression("!'${jejari.pii.keys:}'.isBlank()")
	PiiCipher piiCipher(@Value("${jejari.pii.keys}") String keys,
			@Value("${jejari.pii.active-key:}") String activeKey,
			@Value("${jejari.hmac.key:}") String hmacKey) {
		return PiiCipher.fromConfig(keys, activeKey, hmacKey);
	}
}
