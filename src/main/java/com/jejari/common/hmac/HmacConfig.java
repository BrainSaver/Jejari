package com.jejari.common.hmac;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 식별정보 가명처리(HMAC) 빈. 키({@code JEJARI_HMAC_KEY}, Base64 32바이트 이상)가 설정된 곳에서만 만들어집니다.
 * 키가 없는 PC에서도 앱은 뜨고, 이 빈을 쓰는 기능만 "빈이 없다"는 오류를 냅니다.
 * 키는 프로젝트 기간 동안 바꾸지 않습니다 (바꾸면 이미 저장된 해시와 이어지지 않음).
 */
@Configuration
public class HmacConfig {

	@Bean
	@ConditionalOnExpression("!'${jejari.hmac.key:}'.isBlank()")
	Pseudonymizer pseudonymizer(@Value("${jejari.hmac.key}") String base64Key) {
		return Pseudonymizer.fromBase64(base64Key);
	}
}
