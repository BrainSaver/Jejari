package com.jejari.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class SpillBufferTest {
	@TempDir
	Path dir;

	@Test
	void 적고_닫으면_다시_보낼_파일로_나온다() throws Exception {
		SpillBuffer b = new SpillBuffer(dir, 1024 * 1024);
		assertThat(b.append("{\"a\":1}")).isTrue();
		assertThat(b.append("{\"a\":2}")).isTrue();
		// 쓰는 중인 파일은 ready()가 닫고 넘긴다. 그 뒤 로그는 새 파일로
		List<Path> ready = b.ready();
		assertThat(ready).hasSize(1);
		assertThat(Files.readAllLines(ready.get(0))).containsExactly("{\"a\":1}", "{\"a\":2}");
		assertThat(b.append("{\"a\":3}")).isTrue();
		assertThat(b.ready()).hasSize(2);
		b.delete(ready.get(0));
		assertThat(b.pendingBytes()).isEqualTo("{\"a\":3}\n".length());
	}

	@Test
	void 상한을_넘으면_적지_않는다() {
		SpillBuffer b = new SpillBuffer(dir, 20);
		assertThat(b.append("0123456789")).isTrue(); // 11바이트
		assertThat(b.append("0123456789")).isFalse(); // 22 > 20
	}

	@Test
	void 꺼져_있으면_아무것도_안_한다() {
		SpillBuffer b = new SpillBuffer(null, 1024);
		assertThat(b.enabled()).isFalse();
		assertThat(b.append("x")).isFalse();
		assertThat(b.ready()).isEmpty();
	}

	@Test
	void 폴더를_만들_수_없으면_앱을_막지_않고_끈다() throws Exception {
		Path file = dir.resolve("file");
		Files.writeString(file, "x"); // 파일 아래에는 폴더를 만들 수 없다
		SpillBuffer b = new SpillBuffer(file.resolve("spill"), 1024);
		assertThat(b.enabled()).isFalse();
		assertThat(b.append("x")).isFalse();
	}

	@Test
	void 다시_만들면_남은_크기를_이어서_센다() {
		SpillBuffer b = new SpillBuffer(dir, 1024);
		b.append("abc");
		b.close();
		assertThat(new SpillBuffer(dir, 1024).pendingBytes()).isEqualTo(4);
	}

	/**
	 * 2026-11-20 실측(Kafka가 멈춘 채 재시작하면 70% 유실)을 단위로: Kafka가 없을 때 보낸 로그가 종료 뒤
	 * 하나도 빠짐없이 디스크에 있고, 다음 기동이 그 파일을 다시 보내려고 꺼낸다.
	 */
	@Test
	void Kafka가_없으면_종료_때_디스크에_남고_다음_기동이_다시_보낸다() throws Exception {
		JsonMapper json = JsonMapper.builder().build();
		BehaviorPublisher p = new BehaviorPublisher("localhost:1", json, dir.toString(), 16);
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			String id = UUID.randomUUID().toString();
			ids.add(id);
			p.publish(new BehaviorEvent(id, "VIEW_TRAIN", "2026-11-20T10:00:00+09:00", null, null, null,
					"a".repeat(64), null, "WEB", null, 200, Map.of()));
		}
		p.destroy();
		assertThat(p.droppedCount() + p.failedCount()).as("진짜 유실").isZero();

		List<String> onDisk = new ArrayList<>();
		try (var s = Files.list(dir)) {
			for (Path f : s.toList()) {
				for (String line : Files.readAllLines(f)) {
					onDisk.add(json.readValue(line, BehaviorEvent.class).eventId());
				}
			}
		}
		assertThat(onDisk).containsExactlyInAnyOrderElementsOf(ids);

		// 다음 기동: 3초 뒤 다시 보내기 → Kafka가 여전히 없으니 다시 디스크로 (유실 없이 돌고 돈다)
		BehaviorPublisher next = new BehaviorPublisher("localhost:1", json, dir.toString(), 16);
		try {
			for (int w = 0; w < 100 && next.replayedCount() == 0; w++) {
				Thread.sleep(100);
			}
			assertThat(next.replayedCount()).isPositive();
		} finally {
			next.destroy();
		}
		assertThat(next.droppedCount() + next.failedCount()).isZero();
	}
}
