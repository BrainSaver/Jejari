package com.jejari.queue.dev;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.jejari.queue.port.MemberIdentityPort;

/** 개발·테스트용. 등록하지 않은 회원은 ACTIVE에 ciHash = "dev-ci-{id}"로 만들어 준다. */
public class DevMemberIdentityPort implements MemberIdentityPort {

	private final Map<Long, MemberIdentity> members = new ConcurrentHashMap<>();

	public void register(MemberIdentity identity) {
		members.put(identity.memberId(), identity);
	}

	public void clear() {
		members.clear();
	}

	@Override
	public Optional<MemberIdentity> find(long memberId) {
		return Optional.of(members.computeIfAbsent(memberId,
				id -> new MemberIdentity(id, "ACTIVE", "dev-ci-" + id, List.of(), List.of())));
	}
}
