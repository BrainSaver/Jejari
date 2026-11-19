package com.jejari.admin.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jejari.admin.deploy.DeployAdminService.CommandRunner.Result;
import com.jejari.admin.deploy.DeployApprovalService.Kind;
import com.jejari.common.error.BusinessException;

/** BLOCKED 승인: 막힌 파일 분류, DB 패치 적용, 모두 끝나야 승인, 승인은 그 커밋 하나에만 */
class DeployApprovalServiceTest {

	static final String SHA = "0dd4c75" + "a".repeat(33);

	@TempDir
	Path app;
	List<List<String>> calls = new ArrayList<>();
	List<String> stdins = new ArrayList<>();
	String sqlOutput = "Constraint dropped.\nTable altered.\n";
	int sqlExit = 0;
	Clock clock = Clock.fixed(Instant.parse("2026-11-19T03:00:00Z"), ZoneId.of("Asia/Seoul"));

	DeployApprovalService service(boolean enabled) {
		var deploy = new DeployAdminService(enabled, Set.of(1L), app, app, cmd -> {
			calls.add(cmd);
			return new Result(0, cmd.contains("is-active") ? "inactive\n" : "");
		}, clock);
		return new DeployApprovalService(enabled, Set.of(1L), app, app, "jejari-oracle-1", "jejari", "s3cret", "FREEPDB1",
				(cmd, stdin, env, timeout) -> {
					calls.add(cmd);
					if (cmd.get(0).equals("git") && cmd.contains("log")) return new Result(0, "Merge into DongGyun\n");
					if (cmd.get(0).equals("git") && cmd.contains("show")) return new Result(0, "ALTER TABLE notification ...;\n");
					stdins.add(stdin);
					return new Result(sqlExit, sqlOutput);
				}, deploy, clock);
	}

	@BeforeEach
	void files() throws Exception {
		Files.writeString(app.resolve("autodeploy.state"),
				"2026-11-19 16:22:25 BLOCKED 0dd4c75 DB·인프라 변경이 있어 자동 배포를 멈춤 → …\n");
		Files.writeString(app.resolve("autodeploy.blocked"), SHA + "\ndb/patch/v30.sql\ndocs/erd/jejari-erd.html\ndeploy/jejari-model.timer\n");
	}

	@Test
	void 막힌_파일을_패치_문서_서버작업으로_나눈다() {
		var b = service(true).blocked(1L);
		assertThat(b.blocked()).isTrue();
		assertThat(b.shortSha()).isEqualTo("0dd4c75");
		assertThat(b.subject()).contains("DongGyun");
		assertThat(b.items()).extracting(DeployApprovalService.Item::kind).containsExactly(Kind.PATCH, Kind.INFO, Kind.MANUAL);
		assertThat(b.items().get(0).done()).isFalse();
		assertThat(b.items().get(1).done()).isTrue();
		assertThat(b.items().get(2).how()).contains("setup-server.sh");
		assertThat(b.canAct()).isTrue();
		assertThat(service(true).blocked(2L).canAct()).isFalse();
	}

	@Test
	void BLOCKED가_아니거나_파일이_다른_커밋이면_보여주지_않는다() throws Exception {
		Files.writeString(app.resolve("autodeploy.state"), "2026-11-19 16:30:00 OK 0dd4c75 자동 배포 완료\n");
		assertThat(service(true).blocked(1L).blocked()).isFalse();
		Files.writeString(app.resolve("autodeploy.state"), "2026-11-19 16:30:00 BLOCKED 1234567 …\n");
		assertThat(service(true).blocked(1L).blocked()).isFalse();
		assertThat(service(false).blocked(1L).blocked()).isFalse();
	}

	@Test
	void 패치는_비밀번호를_명령줄이_아닌_표준입력으로_넘기고_성공을_기록한다() throws Exception {
		var r = service(true).applyPatch(1L, SHA, "db/patch/v30.sql");
		assertThat(r.ok()).isTrue();
		List<String> docker = calls.stream().filter(c -> c.get(0).equals("docker")).findFirst().orElseThrow();
		assertThat(String.join(" ", docker)).doesNotContain("s3cret").contains("/nolog");
		assertThat(stdins.get(0)).startsWith("CONNECT jejari/\"s3cret\"@//localhost:1521/FREEPDB1\n").contains("ALTER TABLE");
		assertThat(Files.readString(app.resolve("db-patches.applied"))).contains("db/patch/v30.sql admin#1 OK");
		assertThat(service(true).blocked(1L).items().get(0).done()).isTrue();
	}

	@Test
	void ORA_오류가_나오면_실패로_기록하고_적용된_것으로_보지_않는다() {
		sqlOutput = "ORA-02443: Cannot drop constraint\n";
		var r = service(true).applyPatch(1L, SHA, "db/patch/v30.sql");
		assertThat(r.ok()).isFalse();
		assertThat(service(true).blocked(1L).items().get(0).done()).isFalse();
	}

	@Test
	void 막힌_목록에_없거나_패치가_아닌_파일은_실행하지_않는다() {
		assertThatThrownBy(() -> service(true).applyPatch(1L, SHA, "db/patch/v99.sql")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service(true).applyPatch(1L, SHA, "deploy/jejari-model.timer")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service(true).applyPatch(2L, SHA, "db/patch/v30.sql")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service(true).applyPatch(1L, "b".repeat(40), "db/patch/v30.sql")).isInstanceOf(BusinessException.class);
		assertThat(calls).noneMatch(c -> c.get(0).equals("docker"));
	}

	@Test
	void 패치가_남았거나_서버작업을_체크하지_않으면_승인할_수_없다() {
		var s = service(true);
		assertThatThrownBy(() -> s.approve(1L, SHA, List.of("deploy/jejari-model.timer")))
				.isInstanceOf(BusinessException.class).hasMessageContaining("DB 패치");
		s.applyPatch(1L, SHA, "db/patch/v30.sql");
		assertThatThrownBy(() -> s.approve(1L, SHA, List.of())).isInstanceOf(BusinessException.class)
				.hasMessageContaining("처리함");
		assertThat(Files.exists(app.resolve("autodeploy.approved"))).isFalse();
	}

	@Test
	void 승인하면_그_커밋을_기록하고_자동_배포를_시작한다() throws Exception {
		var s = service(true);
		s.applyPatch(1L, SHA, "db/patch/v30.sql");
		var b = s.approve(1L, SHA, List.of("deploy/jejari-model.timer"));
		assertThat(Files.readString(app.resolve("autodeploy.approved"))).startsWith(SHA + " 2026-11-19 12:00:00 admin#1");
		assertThat(b.approvedAt()).contains("admin#1");
		assertThat(calls).contains(List.of("sudo", "-n", "systemctl", "start", "--no-block", DeployAdminService.UNIT));
		// 연타 방지 기록은 배포 버튼과 같은 파일, 승인 기록은 따로
		assertThat(Files.readString(app.resolve("deploy-requests.log"))).isEqualTo("2026-11-19 12:00:00 admin#1\n");
		assertThat(Files.readString(app.resolve("deploy-approvals.log"))).contains("승인 " + SHA);
	}
}
