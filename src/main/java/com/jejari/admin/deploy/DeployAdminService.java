package com.jejari.admin.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.ErrorCode;

/**
 * 공용 서버 수동 배포 (관리자 화면 A-08). 버튼은 배포를 직접 하지 않고, 서버의 자동 배포 장치
 * ({@code jejari-autodeploy.service} → {@code deploy/auto-deploy.sh})를 지금 한 번 실행시킨다.
 * 그래서 "CI 성공한 main만", "ERD·DB 패치가 있으면 멈춤", 동시 실행 잠금, Discord 경보가 그대로 적용된다.
 *
 * <ul>
 *   <li>앱이 자기 자신을 재시작하는 일이라 {@code systemctl start --no-block}으로 시작만 걸고 바로 돌아온다.</li>
 *   <li>켜는 스위치({@code jejari.ops.deploy.enabled})와 누를 수 있는 관리자 번호 목록은 서버 {@code .env}에만 둔다.
 *       로컬·팀원 PC에서는 꺼져 있다.</li>
 *   <li>누가 눌렀는지 {@code deploy-requests.log}에 한 줄씩 남긴다 (앱 2대가 같은 파일을 씀).</li>
 *   <li>프로젝트가 끝나면 이 기능은 지운다 (웹에서 서버 명령을 실행하는 기능이라).</li>
 * </ul>
 */
@Service
public class DeployAdminService {

	private static final Logger log = LoggerFactory.getLogger(DeployAdminService.class);
	static final String UNIT = "jejari-autodeploy.service";
	static final Duration MIN_INTERVAL = Duration.ofSeconds(60);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** 명령 실행 (테스트에서 바꿔 끼움) */
	public interface CommandRunner {
		Result run(List<String> command);

		record Result(int exit, String output) {
		}
	}

	public record Status(boolean enabled, boolean canTrigger, boolean running, String deployedSha, String deployedSubject,
			String lastState, String lastStateAt, String lastMessage, String lastRequest) {
	}

	private final boolean enabled;
	private final Set<Long> adminIds;
	private final Path appDir;
	private final Path repoDir;
	private final CommandRunner runner;
	private final Clock clock;

	@Autowired
	public DeployAdminService(@Value("${jejari.ops.deploy.enabled:false}") boolean enabled,
			@Value("${jejari.ops.deploy.admin-ids:}") String adminIds,
			@Value("${jejari.ops.deploy.app-dir:${user.home}/jejari-app}") String appDir,
			@Value("${jejari.ops.deploy.repo-dir:${user.home}/Jejari}") String repoDir) {
		this(enabled, parseIds(adminIds), Path.of(appDir), Path.of(repoDir), DeployAdminService::exec,
				Clock.system(SEOUL));
	}

	DeployAdminService(boolean enabled, Set<Long> adminIds, Path appDir, Path repoDir, CommandRunner runner, Clock clock) {
		this.enabled = enabled;
		this.adminIds = adminIds;
		this.appDir = appDir;
		this.repoDir = repoDir;
		this.runner = runner;
		this.clock = clock;
	}

	static Set<Long> parseIds(String csv) {
		return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> s.matches("\\d+")).map(Long::valueOf)
				.collect(Collectors.toUnmodifiableSet());
	}

	public Status status(long adminId) {
		if (!enabled) {
			return new Status(false, false, false, null, null, null, null, null, null);
		}
		String sha = firstLine(appDir.resolve("deployed-sha"));
		String subject = null;
		if (sha != null && sha.matches("[0-9a-f]{7,40}")) {
			var r = runner.run(List.of("git", "-C", repoDir.toString(), "log", "-1", "--format=%s", sha));
			subject = r.exit() == 0 ? r.output().strip() : null;
		}
		// 예: "2026-11-19 11:39:30 OK 7184660 앱에 영향 없는 변경만 …"
		String st = firstLine(appDir.resolve("autodeploy.state"));
		String at = null, state = null, msg = null;
		if (st != null && st.length() > 20) {
			at = st.substring(0, 19);
			String[] rest = st.substring(20).split(" ", 2);
			state = rest[0];
			msg = rest.length > 1 ? rest[1] : "";
		}
		return new Status(true, adminIds.contains(adminId), running(), sha == null ? null : sha.substring(0, Math.min(7, sha.length())),
				subject, state, at, msg, lastLine(appDir.resolve("deploy-requests.log")));
	}

	/** 배포 장치를 지금 한 번 실행시킨다. 시작만 걸고 바로 돌아온다 */
	public Status trigger(long adminId) {
		if (!enabled) {
			throw new BusinessException(DeployErrorCode.DEPLOY_DISABLED);
		}
		if (!adminIds.contains(adminId)) {
			throw new BusinessException(DeployErrorCode.DEPLOY_NOT_ALLOWED);
		}
		if (running()) {
			throw new BusinessException(DeployErrorCode.DEPLOY_RUNNING);
		}
		Instant last = lastRequestAt();
		if (last != null && Duration.between(last, clock.instant()).compareTo(MIN_INTERVAL) < 0) {
			throw new BusinessException(DeployErrorCode.DEPLOY_TOO_SOON);
		}
		var r = runner.run(List.of("sudo", "-n", "systemctl", "start", "--no-block", UNIT));
		if (r.exit() != 0) {
			log.warn("수동 배포 시작 실패 (관리자 #{}): exit {} {}", adminId, r.exit(), r.output().strip());
			throw new BusinessException(DeployErrorCode.DEPLOY_START_FAILED);
		}
		String line = LocalDateTime.now(clock).format(TS) + " admin#" + adminId;
		try {
			Files.writeString(appDir.resolve("deploy-requests.log"), line + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.warn("수동 배포 기록을 남기지 못함: {}", e.toString());
		}
		log.info("수동 배포 시작 (관리자 #{})", adminId);
		return status(adminId);
	}

	private boolean running() {
		// oneshot 서비스는 도는 동안 activating. 권한 없이 읽을 수 있다
		var r = runner.run(List.of("systemctl", "is-active", UNIT));
		String s = r.output().strip();
		return s.equals("activating") || s.equals("active") || s.equals("reloading");
	}

	private Instant lastRequestAt() {
		String l = lastLine(appDir.resolve("deploy-requests.log"));
		if (l == null || l.length() < 19) {
			return null;
		}
		try {
			return LocalDateTime.parse(l.substring(0, 19), TS).atZone(SEOUL).toInstant();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String firstLine(Path p) {
		try {
			List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
			return lines.isEmpty() ? null : lines.get(0).strip();
		} catch (IOException e) {
			return null;
		}
	}

	private static String lastLine(Path p) {
		try {
			List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
			for (int i = lines.size() - 1; i >= 0; i--) {
				if (!lines.get(i).isBlank()) {
					return lines.get(i).strip();
				}
			}
			return null;
		} catch (IOException e) {
			return null;
		}
	}

	private static CommandRunner.Result exec(List<String> command) {
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return new CommandRunner.Result(124, "시간 초과");
			}
			return new CommandRunner.Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			return new CommandRunner.Result(127, e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new CommandRunner.Result(130, "중단됨");
		}
	}

	enum DeployErrorCode implements ErrorCode {
		DEPLOY_DISABLED(HttpStatus.NOT_FOUND, "이 서버에서는 수동 배포가 꺼져 있습니다."),
		DEPLOY_NOT_ALLOWED(HttpStatus.FORBIDDEN, "수동 배포를 누를 수 있는 관리자가 아닙니다."),
		DEPLOY_RUNNING(HttpStatus.CONFLICT, "배포가 이미 진행 중입니다. 끝난 뒤 다시 눌러 주세요."),
		DEPLOY_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "방금 배포를 시작했습니다. 1분 뒤 다시 눌러 주세요."),
		DEPLOY_START_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "배포를 시작하지 못했습니다. 서버 권한 설정(sudoers)을 확인해 주세요.");

		private final HttpStatus status;
		private final String message;

		DeployErrorCode(HttpStatus status, String message) {
			this.status = status;
			this.message = message;
		}

		@Override
		public String code() {
			return name();
		}

		@Override
		public HttpStatus status() {
			return status;
		}

		@Override
		public String message() {
			return message;
		}
	}
}
