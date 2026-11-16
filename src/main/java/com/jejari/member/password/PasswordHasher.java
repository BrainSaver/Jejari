package com.jejari.member.password;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.member.AuthPolicy;
import com.jejari.member.AuthPolicy.Argon2Params;
import com.jejari.member.MemberAuthEnabled;

/**
 * 비밀번호 해싱 (회원가입·로그인 공용). Argon2id + pepper.
 *
 * <pre>
 * 저장값 = Argon2id( Base64( HMAC-SHA256(pepper, 비밀번호) ) )   → PHC 문자열 $argon2id$v=19$m=…,t=…,p=…$salt$hash
 * </pre>
 *
 * <ul>
 *   <li><b>pepper</b>: DB 밖(환경변수)에 두는 비밀값. DB만 유출되면 해시를 대입 공격할 수 없다. 키 ID를
 *       member_credential.pepper_key_id에 남겨 교체할 수 있다 (새 키로 바뀌면 다음 로그인 때 재해싱).</li>
 *   <li><b>HMAC 먼저</b>: 비밀번호 길이와 관계없이 Argon2 입력이 44자로 고정된다.</li>
 *   <li><b>동시 수 제한</b>: 해시 1회에 약 19 MiB·수십 ms가 들어, 로그인 폭주로 서버 메모리가 바닥나지 않게
 *       {@code auth.max_concurrent_hash}개까지만 동시에 계산한다. 자리가 안 나면 503.</li>
 *   <li><b>더미 검증</b>: 없는 계정도 같은 비용으로 검증해 응답 시간으로 계정 존재를 알 수 없게 한다.</li>
 * </ul>
 */
@Component
@MemberAuthEnabled
public class PasswordHasher {

	public static final String ALGO = "ARGON2ID";
	private static final int SALT_BYTES = 16;
	private static final int HASH_BYTES = 32;
	private static final long ACQUIRE_TIMEOUT_MS = 3000;

	private final Map<String, SecretKeySpec> peppers;
	private final String activePepper;
	private final AuthPolicy policy;
	private final Semaphore slots;
	private final Map<Argon2Params, Argon2PasswordEncoder> encoders = new ConcurrentHashMap<>();
	private final String dummyHash;

	public PasswordHasher(@Value("${jejari.auth.pepper-keys}") String pepperKeys,
			@Value("${jejari.auth.pepper-active:}") String activePepper,
			@Value("${jejari.hmac.key:}") String hmacKey,
			@Value("${jejari.pii.keys:}") String piiKeys,
			AuthPolicy policy) {
		this.peppers = parseKeys(pepperKeys, hmacKey, piiKeys);
		if (activePepper == null || !peppers.containsKey(activePepper.strip())) {
			throw new IllegalStateException("JEJARI_PEPPER_ACTIVE가 JEJARI_PEPPER_KEYS에 있는 키 ID가 아닙니다");
		}
		this.activePepper = activePepper.strip();
		this.policy = policy;
		this.slots = new Semaphore((int) policy.number(AuthPolicy.MAX_CONCURRENT_HASH), true);
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		this.dummyHash = encoder(policy.argon2()).encode(Base64.getEncoder().encodeToString(random));
	}

	/** 가입·재해싱용. 지금 정책 파라미터와 현재 pepper로 만든다. */
	public Hashed hash(String raw) {
		Argon2Params params = policy.argon2();
		String peppered = pepper(activePepper, raw);
		return withSlot(() -> new Hashed(encoder(params).encode(peppered), params.toString(), activePepper));
	}

	/** 저장된 해시와 비교. pepper 키 ID를 모르면(삭제된 키) false. */
	public boolean verify(String raw, String storedHash, String pepperKeyId) {
		if (pepperKeyId == null || !peppers.containsKey(pepperKeyId)) {
			dummyVerify(raw);
			return false;
		}
		String peppered = pepper(pepperKeyId, raw);
		// 저장된 PHC 문자열에 파라미터가 들어 있어 예전 파라미터로 만든 해시도 검증된다
		return withSlot(() -> encoder(policy.argon2()).matches(peppered, storedHash));
	}

	/** 없는 계정·탈퇴 계정에도 같은 비용을 쓴다 (결과는 버림). */
	public void dummyVerify(String raw) {
		String peppered = pepper(activePepper, raw == null ? "" : raw);
		withSlot(() -> encoder(policy.argon2()).matches(peppered, dummyHash));
	}

	/** 파라미터가 정책보다 약하거나 pepper가 현재 키가 아니면 다음 로그인 때 다시 해싱한다. */
	public boolean needsRehash(String storedHash, String pepperKeyId) {
		return !activePepper.equals(pepperKeyId) || encoder(policy.argon2()).upgradeEncoding(storedHash);
	}

	private Argon2PasswordEncoder encoder(Argon2Params p) {
		return encoders.computeIfAbsent(p,
				k -> new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, k.parallelism(), k.memoryKib(), k.iterations()));
	}

	private String pepper(String keyId, String raw) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(peppers.get(keyId));
			return Base64.getEncoder().encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA256을 쓸 수 없습니다", e);
		}
	}

	private <T> T withSlot(java.util.function.Supplier<T> work) {
		boolean acquired;
		try {
			acquired = slots.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
		}
		if (!acquired) {
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
		}
		try {
			return work.get();
		} finally {
			slots.release();
		}
	}

	/** p1:&lt;base64 32바이트&gt;,p2:… 를 읽는다. 32바이트가 아니거나 HMAC·개인정보 키와 같으면 기동을 거부한다. */
	static Map<String, SecretKeySpec> parseKeys(String spec, String hmacKey, String piiKeys) {
		if (spec == null || spec.isBlank()) {
			throw new IllegalStateException("JEJARI_PEPPER_KEYS가 설정되지 않았습니다");
		}
		Map<String, SecretKeySpec> out = new LinkedHashMap<>();
		for (String entry : spec.split(",")) {
			String e = entry.strip();
			if (e.isEmpty()) {
				continue;
			}
			int colon = e.indexOf(':');
			if (colon <= 0) {
				throw new IllegalStateException("JEJARI_PEPPER_KEYS 형식은 p1:<base64>,p2:<base64> 입니다");
			}
			String id = e.substring(0, colon).strip();
			if (!id.matches("[a-z][a-z0-9]{0,19}")) {
				throw new IllegalStateException("pepper 키 ID는 소문자·숫자 20자 이내입니다");
			}
			byte[] key = decode(e.substring(colon + 1));
			try {
				if (key.length != 32) {
					throw new IllegalStateException("pepper 키 " + id + "는 32바이트여야 합니다");
				}
				if (sameAsAny(key, hmacKey, piiKeys)) {
					throw new IllegalStateException("pepper 키 " + id + "가 HMAC·개인정보 암호화 키와 같습니다. 키는 용도마다 따로 만드세요");
				}
				for (SecretKeySpec other : out.values()) {
					if (MessageDigest.isEqual(other.getEncoded(), key)) {
						throw new IllegalStateException("pepper 키가 서로 같습니다");
					}
				}
				if (out.put(id, new SecretKeySpec(key, "HmacSHA256")) != null) {
					throw new IllegalStateException("pepper 키 ID " + id + "가 중복됩니다");
				}
			} finally {
				Arrays.fill(key, (byte) 0);
			}
		}
		if (out.isEmpty()) {
			throw new IllegalStateException("JEJARI_PEPPER_KEYS가 비어 있습니다");
		}
		return Map.copyOf(out);
	}

	private static boolean sameAsAny(byte[] key, String hmacKey, String piiKeys) {
		if (hmacKey != null && !hmacKey.isBlank() && MessageDigest.isEqual(key, decodeQuiet(hmacKey))) {
			return true;
		}
		if (piiKeys != null) {
			for (String entry : piiKeys.split(",")) {
				int colon = entry.indexOf(':');
				if (colon > 0 && MessageDigest.isEqual(key, decodeQuiet(entry.substring(colon + 1)))) {
					return true;
				}
			}
		}
		return false;
	}

	private static byte[] decode(String b64) {
		try {
			return Base64.getDecoder().decode(b64.strip());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("pepper 키가 올바른 Base64가 아닙니다"); // 값은 싣지 않는다
		}
	}

	private static byte[] decodeQuiet(String b64) {
		try {
			return Base64.getDecoder().decode(b64.strip());
		} catch (IllegalArgumentException e) {
			return new byte[0];
		}
	}

	/** @param hash PHC 문자열, @param params 저장용 파라미터 문자열, @param pepperKeyId 쓴 pepper 키 ID */
	public record Hashed(String hash, String params, String pepperKeyId) {
		@Override
		public String toString() {
			return "Hashed[params=" + params + ", pepper=" + pepperKeyId + "]"; // 해시값은 로그에 남기지 않는다
		}
	}
}
