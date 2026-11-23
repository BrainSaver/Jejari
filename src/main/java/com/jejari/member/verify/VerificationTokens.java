package com.jejari.member.verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiDecryptException;
import com.jejari.common.pii.PiiField;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.verify.MockIdentityProvider.Provider;

/**
 * 본인인증 → 가입 사이를 잇는 1회용 토큰.
 *
 * <ul>
 *   <li>토큰은 의미 없는 무작위 256비트(base64url 43자). 내용은 서버(Redis)에만 있다.</li>
 *   <li>Redis 키는 토큰의 SHA-256 해시 {@code verify:{hash}} — Redis가 유출돼도 토큰을 만들 수 없다.</li>
 *   <li>휴대폰 번호는 Redis에서도 AES-256-GCM으로 암호화 (AAD에 토큰 해시를 넣어 다른 키로 옮기면 복호화 실패).</li>
 *   <li>가입 때 {@code GETDEL}로 읽으면서 지운다 → 같은 토큰으로 두 번 가입할 수 없다 (동시 요청도 하나만 성공).</li>
 *   <li>유효 시간 {@code auth.verify_token_ttl_sec}(기본 10분) 뒤 자동 삭제.</li>
 * </ul>
 * 실패 이유(없음·만료·이미 씀·형식 오류)는 구분하지 않고 모두 {@code AUTH_VERIFY_FAILED}.
 */
@Component
@MemberAuthEnabled
public class VerificationTokens {

	private static final PiiField PHONE = new PiiField("verify_session", "phone");
	private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Encoder B64 = Base64.getEncoder();
	private static final Base64.Decoder B64D = Base64.getDecoder();

	private final StringRedisTemplate redis;
	private final PiiCipher cipher;
	private final AuthPolicy policy;
	private final Clock clock;

	public VerificationTokens(StringRedisTemplate redis, PiiCipher cipher, AuthPolicy policy, Clock clock) {
		this.redis = redis;
		this.cipher = cipher;
		this.policy = policy;
		this.clock = clock;
	}

	public Issued issue(VerifiedIdentity id) {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String token = B64URL.encodeToString(raw);
		String hash = sha256Hex(token);
		Duration ttl = Duration.ofSeconds(policy.number(AuthPolicy.VERIFY_TOKEN_TTL_SEC));
		String value = String.join("|", "v1", id.ciHash(), id.provider().name(),
				Long.toString(id.verifiedAt().toEpochMilli()),
				B64.encodeToString(id.name().getBytes(StandardCharsets.UTF_8)),
				cipher.encrypt(id.phone(), PHONE, hash).cipherText());
		redis.opsForValue().set(key(hash), value, ttl);
		return new Issued(token, ttl.toSeconds());
	}

	/** 토큰을 읽기만 한다 (가입 입력 검증·비밀번호 해싱 전에 토큰이 살아 있는지 확인). */
	public VerifiedIdentity peek(String token) {
		String hash = hashOf(token);
		return decode(hash, redis.opsForValue().get(key(hash)));
	}

	/** 토큰을 읽으면서 지운다. 동시에 두 번 불러도 하나만 성공한다. */
	public VerifiedIdentity consume(String token) {
		String hash = hashOf(token);
		return decode(hash, redis.opsForValue().getAndDelete(key(hash)));
	}

	/**
	 * {@link #consume}로 지운 토큰을 되돌린다. 가입 저장이 실패해(DB 바쁨 503 등) 회원이 만들어지지 않았을 때만 부른다.
	 * 되돌리지 않으면 "잠시 후 다시 시도"해도 토큰이 없어 본인인증부터 다시 해야 한다 (11-23).
	 * 원래 인증 시각을 그대로 써서 유효 시간이 늘어나지 않고, 이미 같은 키가 있으면 덮어쓰지 않는다 (SET NX).
	 *
	 * @return 되돌렸으면 true (유효 시간이 이미 지났으면 false)
	 */
	public boolean restore(String token, VerifiedIdentity id) {
		String hash = hashOf(token);
		Duration ttl = Duration.ofSeconds(policy.number(AuthPolicy.VERIFY_TOKEN_TTL_SEC));
		Duration left = Duration.between(clock.instant(), id.verifiedAt().plus(ttl));
		if (left.isNegative() || left.isZero()) {
			return false;
		}
		String value = String.join("|", "v1", id.ciHash(), id.provider().name(),
				Long.toString(id.verifiedAt().toEpochMilli()),
				B64.encodeToString(id.name().getBytes(StandardCharsets.UTF_8)),
				cipher.encrypt(id.phone(), PHONE, hash).cipherText());
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(hash), value, left));
	}

	private VerifiedIdentity decode(String hash, String value) {
		if (value == null) {
			throw fail();
		}
		String[] f = value.split("\\|", -1);
		if (f.length != 6 || !"v1".equals(f[0])) {
			throw fail();
		}
		try {
			Instant verifiedAt = Instant.ofEpochMilli(Long.parseLong(f[3]));
			Duration ttl = Duration.ofSeconds(policy.number(AuthPolicy.VERIFY_TOKEN_TTL_SEC));
			if (verifiedAt.plus(ttl).isBefore(clock.instant())) {
				throw fail(); // Redis TTL과 별개로 앱 시계로도 만료 확인
			}
			String name = new String(B64D.decode(f[4]), StandardCharsets.UTF_8);
			String phone = cipher.decrypt(f[5], PHONE, hash);
			return new VerifiedIdentity(f[1], name, phone, Provider.valueOf(f[2]), verifiedAt);
		} catch (IllegalArgumentException | PiiDecryptException e) {
			throw fail();
		}
	}

	private static String hashOf(String token) {
		if (token == null || !TOKEN.matcher(token).matches()) {
			throw fail();
		}
		return sha256Hex(token);
	}

	private static String key(String hash) {
		return "verify:" + hash;
	}

	private static String sha256Hex(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static BusinessException fail() {
		return new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
	}

	/** @param token 클라이언트에 주는 값, @param expiresIn 남은 초 */
	public record Issued(String token, long expiresIn) {
		@Override
		public String toString() {
			return "Issued[expiresIn=" + expiresIn + "]";
		}
	}
}
