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
import com.jejari.common.error.BusinessException;

/** 수동 배포 버튼: 켜기 스위치·허용 관리자·진행 중·연타 방지, 시작만 걸고 돌아오기 */
class DeployAdminServiceTest {

	@TempDir
	Path app;
	List<List<String>> calls = new ArrayList<>();
	String isActive = "inactive";
	int startExit = 0;
	Clock clock = Clock.fixed(Instant.parse("2026-11-19T03:00:00Z"), ZoneId.of("Asia/Seoul")); // 12:00 서울

	DeployAdminService service(boolean enabled) {
		return new DeployAdminService(enabled, Set.of(1L), app, app, cmd -> {
			calls.add(cmd);
			if (cmd.contains("is-active")) return new Result(0, isActive + "\n");
			if (cmd.get(0).equals("git")) return new Result(0, "fix(seed): 시드 05 범위\n");
			return new Result(startExit, "");
		}, clock);
	}

	@BeforeEach
	void files() throws Exception {
		Files.writeString(app.resolve("deployed-sha"), "a1a34f0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n");
		Files.writeString(app.resolve("autodeploy.state"), "2026-11-19 11:39:30 OK 7184660 앱에 영향 없는 변경만\n");
	}

	@Test
	void 상태는_배포된_커밋과_마지막_판단을_보여준다() {
		var s = service(true).status(1L);
		assertThat(s.deployedSha()).isEqualTo("a1a34f0");
		assertThat(s.deployedSubject()).contains("시드 05");
		assertThat(s.lastState()).isEqualTo("OK");
		assertThat(s.lastStateAt()).isEqualTo("2026-11-19 11:39:30");
		assertThat(s.canTrigger()).isTrue();
		assertThat(service(true).status(2L).canTrigger()).isFalse();
	}

	@Test
	void 시작만_걸고_누가_눌렀는지_남긴다() throws Exception {
		service(true).trigger(1L);
		assertThat(calls).contains(List.of("sudo", "-n", "systemctl", "start", "--no-block", DeployAdminService.UNIT));
		assertThat(Files.readString(app.resolve("deploy-requests.log"))).isEqualTo("2026-11-19 12:00:00 admin#1\n");
	}

	@Test
	void 꺼져_있으면_아무것도_실행하지_않는다() {
		assertThatThrownBy(() -> service(false).trigger(1L)).isInstanceOf(BusinessException.class);
		assertThat(service(false).status(1L).enabled()).isFalse();
		assertThat(calls).isEmpty();
	}

	@Test
	void 허용_목록에_없는_관리자는_누를_수_없다() {
		assertThatThrownBy(() -> service(true).trigger(2L)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("관리자가 아닙니다");
		assertThat(calls).noneMatch(c -> c.contains("start"));
	}

	@Test
	void 배포가_진행_중이면_다시_시작하지_않는다() {
		isActive = "activating";
		assertThatThrownBy(() -> service(true).trigger(1L)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("진행 중");
	}

	@Test
	void 일분_안에_다시_누르면_거절한다() throws Exception {
		Files.writeString(app.resolve("deploy-requests.log"), "2026-11-19 11:59:30 admin#1\n");
		assertThatThrownBy(() -> service(true).trigger(1L)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("1분");
		Files.writeString(app.resolve("deploy-requests.log"), "2026-11-19 11:58:30 admin#1\n");
		service(true).trigger(1L);
	}

	@Test
	void sudo가_실패하면_알린다() {
		startExit = 1;
		assertThatThrownBy(() -> service(true).trigger(1L)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("sudoers");
	}

	@Test
	void 관리자_번호_목록은_숫자만() {
		assertThat(DeployAdminService.parseIds(" 1, 2,abc,,3 ")).containsExactlyInAnyOrder(1L, 2L, 3L);
		assertThat(DeployAdminService.parseIds("")).isEmpty();
	}
}
