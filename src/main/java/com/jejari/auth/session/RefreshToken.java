package com.jejari.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 리프레시 토큰 = {@code {session_id}.{무작위 32바이트}}.
 *
 * <p>앞부분 session_id로 auth_session 행을 바로 찾고, 뒷부분(비밀)은 SHA-256 해시만 DB에 저장합니다.
 * 32바이트 무작위 값은 추측이 불가능하므로 비밀번호처럼 느린 해시(Argon2)가 필요 없습니다.
 */
public record RefreshToken(String sessionId, String secret) {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");

	public static RefreshToken generate(String sessionId) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return new RefreshToken(sessionId, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
	}

	/** 쿠키 값 해석. 형식이 틀리면 빈 값 (DB 조회 없이 거절). */
	public static Optional<RefreshToken> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		int dot = raw.indexOf('.');
		if (dot < 0) {
			return Optional.empty();
		}
		String sid = raw.substring(0, dot);
		String secret = raw.substring(dot + 1);
		if (!UUID.matcher(sid).matches() || !SECRET.matcher(secret).matches()) {
			return Optional.empty();
		}
		return Optional.of(new RefreshToken(sid, secret));
	}

	/** 클라이언트에 줄 값 (쿠키). */
	public String value() {
		return sessionId + "." + secret;
	}

	/** auth_session.refresh_token_hash에 저장·비교할 값 (SHA-256 hex 64자). */
	public String hash() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 비밀값이 로그에 찍히지 않게 합니다. */
	@Override
	public String toString() {
		return "RefreshToken[sessionId=" + sessionId + ", secret=***]";
	}
}
