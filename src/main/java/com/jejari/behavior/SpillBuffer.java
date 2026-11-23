package com.jejari.behavior;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 행동 로그 디스크 버퍼. Kafka로 못 보낸 로그를 버리지 않고 파일(JSON 한 줄에 한 건)에 적어 두었다가
 * Kafka가 돌아오면 {@link BehaviorPublisher}가 다시 보낸다. 앱이 종료될 때 메모리 큐에 남은 것도 여기로 옮겨
 * 다음 기동 때 보낸다 (Fluent Bit 파일 버퍼와 같은 방식).
 *
 * <ul>
 *   <li>파일: {@code spill-<밀리초>-<순번>.jsonl}. 이름 순서 = 쓴 순서. 지금 쓰는 파일은 다시 보내기 대상에서 뺀다.</li>
 *   <li>한 건 적을 때마다 flush: 앱이 강제 종료(SIGKILL)돼도 적은 것까지는 남게.</li>
 *   <li>상한({@code maxBytes})을 넘으면 적지 않는다 (디스크를 다 채워 Oracle·MongoDB까지 멈추게 하지 않게) → 호출한 쪽이 버림으로 센다.</li>
 *   <li>권한: 폴더 700 · 파일 600. 내용은 가명처리된 행동 로그(해시만, 원문 IP·이메일 없음)지만 다른 계정이 읽지 않게.</li>
 *   <li>같은 로그가 두 번 갈 수 있다 (다시 보내는 중 종료 등). 적재 쪽 event_id 유니크 인덱스가 걸러 낸다.</li>
 * </ul>
 * 앱 인스턴스마다 다른 폴더를 쓴다 (기본값에 포트가 들어감). 두 인스턴스가 같은 파일을 다시 보내지 않게.
 */
public class SpillBuffer {
	private static final Logger log = LoggerFactory.getLogger(SpillBuffer.class);
	private static final String PREFIX = "spill-";
	private static final String SUFFIX = ".jsonl";

	private final Path dir;
	private final long maxBytes;
	private final AtomicLong bytes = new AtomicLong();
	private BufferedWriter writer;
	private Path current;
	private long seq;

	/**
	 * dir가 null이면 꺼짐 (append가 늘 false). 폴더를 만들거나 읽을 수 없어도 앱 기동을 막지 않고 꺼진 채로 둔다
	 * (그때는 예전처럼 재시도 후 버림) — 지켜보는 사람 없는 자동 배포에서 앱이 안 뜨는 것이 로그 유실보다 나쁘다.
	 */
	public SpillBuffer(Path dir, long maxBytes) {
		this.maxBytes = maxBytes;
		Path usable = null;
		if (dir != null) {
			try {
				Files.createDirectories(dir);
				restrict(dir, "rwx------");
				try (Stream<Path> s = Files.list(dir)) {
					bytes.set(s.filter(SpillBuffer::isSpill).mapToLong(SpillBuffer::size).sum());
				}
				usable = dir;
			} catch (IOException | UncheckedIOException | SecurityException e) {
				log.error("행동 로그 디스크 버퍼 폴더를 쓸 수 없어 끔 ({}): {} — Kafka 장애·재시작 때 행동 로그가 빠질 수 있음", dir, e.toString());
			}
		}
		this.dir = usable;
	}

	public boolean enabled() {
		return dir != null;
	}

	/** 한 줄 적는다. 꺼져 있거나 상한을 넘거나 쓰기에 실패하면 false */
	public synchronized boolean append(String jsonLine) {
		if (dir == null) {
			return false;
		}
		byte[] b = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
		if (bytes.get() + b.length > maxBytes) {
			return false;
		}
		try {
			if (writer == null) {
				current = dir.resolve(PREFIX + System.currentTimeMillis() + "-" + (seq++) + SUFFIX);
				writer = Files.newBufferedWriter(current, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE);
				restrict(current, "rw-------");
			}
			writer.write(jsonLine);
			writer.write('\n');
			writer.flush();
			bytes.addAndGet(b.length);
			return true;
		} catch (IOException e) {
			log.error("행동 로그를 디스크 버퍼에 적지 못함 ({}): {}", dir, e.toString());
			closeQuietly();
			return false;
		}
	}

	/** 지금 쓰는 파일을 닫는다. 다음 append는 새 파일에 적는다 */
	public synchronized void close() {
		closeQuietly();
	}

	/** 다시 보낼 파일 (오래된 것부터). 지금 쓰는 파일은 닫고 넘긴다 — 그 뒤에 오는 로그는 새 파일로 */
	public List<Path> ready() {
		if (dir == null) {
			return List.of();
		}
		synchronized (this) {
			closeQuietly();
		}
		try (Stream<Path> s = Files.list(dir)) {
			List<Path> out = new ArrayList<>(s.filter(SpillBuffer::isSpill).sorted().toList());
			synchronized (this) {
				if (current != null) {
					out.remove(current);
				}
			}
			return out;
		} catch (IOException e) {
			log.warn("행동 로그 디스크 버퍼 목록을 읽지 못함: {}", e.toString());
			return List.of();
		}
	}

	/** 다 보낸 파일을 지운다 */
	public void delete(Path file) {
		long size = size(file);
		try {
			if (Files.deleteIfExists(file)) {
				bytes.addAndGet(-size);
			}
		} catch (IOException e) {
			log.warn("행동 로그 디스크 버퍼 파일을 지우지 못함 {}: {}", file.getFileName(), e.toString());
		}
	}

	/** 디스크에 남아 있는 바이트 (A-10 지표) */
	public long pendingBytes() {
		return bytes.get();
	}

	private void closeQuietly() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
				// 닫기 실패는 이미 적은 내용에 영향 없음 (한 줄마다 flush)
			}
			writer = null;
			current = null;
		}
	}

	private static boolean isSpill(Path p) {
		String n = p.getFileName().toString();
		return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
	}

	private static long size(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return 0;
		}
	}

	private static void restrict(Path p, String perms) {
		try {
			Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
		} catch (UnsupportedOperationException | IOException e) {
			// Windows 등 POSIX 권한이 없는 파일 시스템 (팀원 PC). 사용자 홈 아래라 그대로 둔다
		}
	}
}
