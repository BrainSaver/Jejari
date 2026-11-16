package com.jejari.queue.port;

import java.util.List;
import java.util.Optional;

/**
 * 대기열이 필요로 하는 회원 정보. 실제 구현은 member·payment_method·member_device(Oracle)를 읽는다 (D·E 담당 모듈).
 * 이 모듈에는 개발용 구현만 있다.
 */
public interface MemberIdentityPort {

    Optional<MemberIdentity> find(long memberId);

    /** status: ACTIVE / RESTRICTED / BANNED (member.status) */
    record MemberIdentity(long memberId, String status, String ciHash,
                          List<String> paymentHashes, List<String> deviceHashes) {
    }
}
