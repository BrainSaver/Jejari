package com.jejari.member.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jejari.member.AuthPolicy;
import com.jejari.member.AuthPolicy.Argon2Params;

class PasswordHasherTest {

	static final String P1 = key(), P2 = key(), HMAC = key(), ENC = key();

	static String key() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

	@SuppressWarnings("unchecked")
	static AuthPolicy policy() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hash = mock(HashOperations.class);
		when(redis.opsForHash()).thenReturn(hash);
		when(hash.get(any(), any())).thenReturn(null); // 정책 캐시가 비어 있으면 ERD 기본값
		return new AuthPolicy(redis);
	}

	static PasswordHasher hasher(String keys, String active) {
		return new PasswordHasher(keys, active, HMAC, "k1:" + ENC, policy());
	}

	final PasswordHasher h = hasher("p1:" + P1, "p1");

	@Test
	void 해싱하고_검증한다_PHC_형식_Argon2id() {
		PasswordHasher.Hashed x = h.hash("Blue-Whale-42");
		assertThat(x.hash()).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
		assertThat(x.params()).isEqualTo("m=19456,t=2,p=1");
		assertThat(x.pepperKeyId()).isEqualTo("p1");
		assertThat(h.verify("Blue-Whale-42", x.hash(), "p1")).isTrue();
		assertThat(h.verify("Blue-Whale-43", x.hash(), "p1")).isFalse();
		assertThat(x.toString()).doesNotContain(x.hash());
	}

	@Test
	void pepper가_다르면_DB_해시만으로는_맞출_수_없다() {
		PasswordHasher.Hashed x = h.hash("Blue-Whale-42");
		PasswordHasher other = hasher("p1:" + P2, "p1"); // 같은 키 ID, 다른 pepper (DB만 가진 공격자)
		assertThat(other.verify("Blue-Whale-42", x.hash(), "p1")).isFalse();
	}

	@Test
	void 모르는_pepper_키_ID면_실패() {
		PasswordHasher.Hashed x = h.hash("Blue-Whale-42");
		assertThat(h.verify("Blue-Whale-42", x.hash(), "p9")).isFalse();
		assertThat(h.verify("Blue-Whale-42", x.hash(), null)).isFalse();
	}

	@Test
	void pepper를_교체하면_예전_해시도_검증되고_재해싱_대상() {
		PasswordHasher.Hashed old = h.hash("Blue-Whale-42");
		PasswordHasher rotated = hasher("p1:" + P1 + ",p2:" + P2, "p2");
		assertThat(rotated.verify("Blue-Whale-42", old.hash(), "p1")).isTrue();
		assertThat(rotated.needsRehash(old.hash(), "p1")).isTrue();
		PasswordHasher.Hashed fresh = rotated.hash("Blue-Whale-42");
		assertThat(fresh.pepperKeyId()).isEqualTo("p2");
		assertThat(rotated.needsRehash(fresh.hash(), "p2")).isFalse();
	}

	@Test
	void 약한_파라미터로_만든_예전_해시는_재해싱_대상() {
		String weak = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 4096, 1)
				.encode("x");
		assertThat(h.needsRehash(weak, "p1")).isTrue();
	}

	@Test
	void 키_설정이_잘못되면_기동_거부() {
		assertThatThrownBy(() -> hasher("", "p1")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> hasher("p1:" + P1, "p2")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> hasher("p1:AAAA", "p1")).isInstanceOf(IllegalStateException.class); // 32바이트 아님
		assertThatThrownBy(() -> hasher("p1:" + HMAC, "p1")).isInstanceOf(IllegalStateException.class); // HMAC 키 재사용
		assertThatThrownBy(() -> hasher("p1:" + ENC, "p1")).isInstanceOf(IllegalStateException.class); // 암호화 키 재사용
		assertThatThrownBy(() -> hasher("p1:" + P1 + ",p2:" + P1, "p1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 정책값이_OWASP_최소보다_약하면_받지_않는다() {
		assertThatThrownBy(() -> Argon2Params.parse("m=4096,t=2,p=1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Argon2Params.parse("m=19456,t=1,p=1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Argon2Params.parse("m=999999,t=2,p=1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Argon2Params.parse("garbage")).isInstanceOf(IllegalArgumentException.class);
		assertThat(Argon2Params.parse(" m=47104, t=1 ,p=1".replace("t=1", "t=2")).memoryKib()).isEqualTo(47104);
	}
}
