package com.jejari.member;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * 회원가입·로그인 빈은 비밀값 세 개(HMAC 키, 개인정보 암호화 키, 비밀번호 pepper)가 모두 있을 때만 만든다.
 * 하나라도 없으면 기능 자체가 꺼진다 (엔드포인트 없음 → 404). 약한 대체값으로 돌지 않게 하기 위해서다 (fail-closed).
 * 키가 없는 PC에서도 다른 파트(대기열 등)는 그대로 뜬다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnExpression("!'${jejari.hmac.key:}'.isBlank() and !'${jejari.pii.keys:}'.isBlank()"
		+ " and !'${jejari.auth.pepper-keys:}'.isBlank()")
public @interface MemberAuthEnabled {
}
