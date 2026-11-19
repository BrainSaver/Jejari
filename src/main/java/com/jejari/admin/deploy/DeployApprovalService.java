package com.jejari.admin.deploy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jejari.admin.deploy.DeployAdminService.CommandRunner.Result;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.ErrorCode;

/**
 * 자동 배포가 BLOCKED(DB·인프라 변경)로 멈췄을 때 관리자 화면(A-08)에서 확인하고 승인한다.
 *
 * <pre>
 * auto-deploy.sh 가 BLOCKED 로 멈춤 → ~/jejari-app/autodeploy.blocked 에 커밋과 막힌 파일 목록을 남김
 *   → 화면이 파일마다 할 일을 보여 줌
 *       DB 패치(db/patch/vNN.sql)  [적용] 버튼 = Oracle 컨테이너에서 그 커밋의 패치 파일을 실행
 *       문서·시드                   서버에 할 일 없음 (자동 통과)
 *       그 밖(Caddy·systemd 등)     서버에서 직접 할 명령을 보여 주고, 승인할 때 "처리함" 체크
 *   → 모두 끝나면 [승인] = autodeploy.approved 에 그 커밋을 적고 자동 배포를 지금 한 번 실행
 *   → auto-deploy.sh 는 승인된 커밋과 고른 커밋이 같을 때만 BLOCKED 를 건너뛰고 배포
 * </pre>
 *
 * 새 sudo 권한이 필요 없다: 앱 계정은 docker 그룹이라 DB 패치를 직접 넣을 수 있고, 배포 시작은 기존 배포 버튼 줄을 쓴다.
 * 승인은 그 커밋 하나에만 걸린다. 그사이 새 커밋이 올라와 다른 커밋이 골라지면 다시 BLOCKED 가 되고 다시 승인해야 한다.
 * 프로젝트가 끝나면 지운다 (웹에서 서버 명령을 실행하는 기능이라. DeployAdminService 와 같은 스위치로 켜고 끔).
 */
@Service
public class DeployApprovalService {

	private static final Logger log = LoggerFactory.getLogger(DeployApprovalService.class);
	static final Pattern PATCH = Pattern.compile("db/patch/v\\d+\\.sql");
	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
	private static final Duration PATCH_TIMEOUT = Duration.ofMinutes(3);
	private static final int MAX_OUTPUT = 8000;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** 표준 입력·환경 변수·시간 제한이 있는 명령 실행 (테스트에서 바꿔 끼움) */
	public interface ProcessRunner {
		Result run(List<String> command, String stdin, Map<String, String> env, Duration timeout);
	}

	public enum Kind {
		/** db/patch/vNN.sql: 화면에서 [적용] */
		PATCH,
		/** 문서·시드: 운영 DB·서버에 할 일 없음 */
		INFO,
		/** Caddy·systemd·sudoers·compose 등: 서버에서 사람이 직접 */
		MANUAL
	}

	public record Item(String path, Kind kind, boolean done, String how) {
	}

	public record Blocked(boolean blocked, String sha, String shortSha, String subject, List<Item> items,
			boolean canAct, boolean ready, String approvedAt) {
		static Blocked none() {
			return new Blocked(false, null, null, null, List.of(), false, false, null);
		}
	}

	public record PatchResult(String path, boolean ok, String output) {
	}

	private final boolean enabled;
	private final Set<Long> adminIds;
	private final Path appDir;
	private final Path repoDir;
	private final String oracleContainer;
	private final String dbUser;
	private final String dbPassword;
	private final String dbService;
	private final ProcessRunner runner;
	private final DeployAdminService deploy;
	private final Clock clock;

	@Autowired
	public DeployApprovalService(@Value("${jejari.ops.deploy.enabled:false}") boolean enabled,
			@Value("${jejari.ops.deploy.admin-ids:}") String adminIds,
			@Value("${jejari.ops.deploy.app-dir:${user.home}/jejari-app}") String appDir,
			@Value("${jejari.ops.deploy.repo-dir:${user.home}/Jejari}") String repoDir,
			@Value("${jejari.ops.deploy.oracle-container:jejari-oracle-1}") String oracleContainer,
			@Value("${spring.datasource.username:jejari}") String dbUser,
			@Value("${spring.datasource.password:}") String dbPassword,
			@Value("${ORACLE_SERVICE:FREEPDB1}") String dbService, DeployAdminService deploy) {
		this(enabled, DeployAdminService.parseIds(adminIds), Path.of(appDir), Path.of(repoDir), oracleContainer, dbUser,
				dbPassword, dbService, DeployApprovalService::exec, deploy, Clock.system(SEOUL));
	}

	DeployApprovalService(boolean enabled, Set<Long> adminIds, Path appDir, Path repoDir, String oracleContainer,
			String dbUser, String dbPassword, String dbService, ProcessRunner runner, DeployAdminService deploy, Clock clock) {
		this.enabled = enabled;
		this.adminIds = adminIds;
		this.appDir = appDir;
		this.repoDir = repoDir;
		this.oracleContainer = oracleContainer;
		this.dbUser = dbUser;
		this.dbPassword = dbPassword;
		this.dbService = dbService;
		this.runner = runner;
		this.deploy = deploy;
		this.clock = clock;
	}

	/** 지금 BLOCKED 인 커밋과 파일별 할 일. BLOCKED 가 아니면 blocked=false */
	public Blocked blocked(long adminId) {
		if (!enabled) {
			return Blocked.none();
		}
		Target t = target();
		if (t == null) {
			return Blocked.none();
		}
		Set<String> applied = appliedPatches();
		List<Item> items = new ArrayList<>();
		for (String p : t.paths()) {
			items.add(classify(p, applied));
		}
		boolean ready = items.stream().allMatch(i -> i.done() || i.kind() == Kind.MANUAL);
		var r = runner.run(List.of("git", "-C", repoDir.toString(), "log", "-1", "--format=%s", t.sha()), null, Map.of(),
				Duration.ofSeconds(10));
		String subject = r.exit() == 0 ? r.output().strip() : null;
		return new Blocked(true, t.sha(), t.sha().substring(0, 7), subject, items, adminIds.contains(adminId), ready,
				approvedAt(t.sha()));
	}

	/** 막힌 커밋의 파일 내용 (승인 전에 무엇이 바뀌는지 보기). 막힌 목록에 있는 파일만 */
	public String file(String sha, String path) {
		Target t = requireTarget(sha);
		if (!t.paths().contains(path)) {
			throw new BusinessException(ApprovalErrorCode.NOT_IN_BLOCKED);
		}
		var r = runner.run(List.of("git", "-C", repoDir.toString(), "show", sha + ":" + path), null, Map.of(),
				Duration.ofSeconds(10));
		if (r.exit() != 0) {
			// 이 커밋에서 지워진 파일
			return "(이 커밋에서 지워진 파일입니다)";
		}
		return cut(r.output());
	}

	/** 막힌 커밋의 db/patch/vNN.sql 을 Oracle 에 넣는다. 패치는 다시 실행해도 안전하게 쓰는 것이 규칙 */
	public synchronized PatchResult applyPatch(long adminId, String sha, String path) {
		requireAdmin(adminId);
		Target t = requireTarget(sha);
		if (!t.paths().contains(path) || !PATCH.matcher(path).matches()) {
			throw new BusinessException(ApprovalErrorCode.NOT_A_PATCH);
		}
		var src = runner.run(List.of("git", "-C", repoDir.toString(), "show", sha + ":" + path), null, Map.of(),
				Duration.ofSeconds(10));
		if (src.exit() != 0) {
			throw new BusinessException(ApprovalErrorCode.NOT_A_PATCH);
		}
		// 비밀번호를 명령줄에 두지 않는다: /nolog 로 띄우고 표준 입력 첫 줄에서 CONNECT (ps·docker inspect 에 안 보임)
		String stdin = "CONNECT " + dbUser + "/\"" + dbPassword + "\"@//localhost:1521/" + dbService + "\n"
				+ src.output() + "\nEXIT\n";
		var r = runner.run(List.of("docker", "exec", "-i", "-e", "NLS_LANG=AMERICAN_AMERICA.AL32UTF8", oracleContainer,
				"bash", "-lc", "sqlplus -S -L /nolog"), stdin, Map.of(), PATCH_TIMEOUT);
		String out = r.output() == null ? "" : r.output();
		boolean ok = r.exit() == 0 && !Pattern.compile("ORA-\\d+|SP2-\\d+").matcher(out).find();
		append("db-patches.applied", now() + " " + sha.substring(0, 7) + " " + path + " admin#" + adminId + " "
				+ (ok ? "OK" : "FAIL"));
		log.info("DB 패치 {} 적용 {} (관리자 #{}, 커밋 {})", path, ok ? "성공" : "실패", adminId, sha.substring(0, 7));
		return new PatchResult(path, ok, cut(out));
	}

	/**
	 * 승인: 패치가 모두 적용됐고 서버 작업(MANUAL)을 모두 "처리함"으로 체크했을 때만.
	 * 승인 파일을 남기고 자동 배포를 지금 한 번 실행한다.
	 */
	public synchronized Blocked approve(long adminId, String sha, Collection<String> confirmed) {
		requireAdmin(adminId);
		Target t = requireTarget(sha);
		Set<String> applied = appliedPatches();
		for (String p : t.paths()) {
			Item i = classify(p, applied);
			if (i.kind() == Kind.PATCH && !i.done()) {
				throw new BusinessException(ApprovalErrorCode.PATCH_PENDING);
			}
			if (i.kind() == Kind.MANUAL && (confirmed == null || !confirmed.contains(p))) {
				throw new BusinessException(ApprovalErrorCode.MANUAL_PENDING);
			}
		}
		try {
			Files.writeString(appDir.resolve("autodeploy.approved"), sha + " " + now() + " admin#" + adminId + "\n",
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BusinessException(ApprovalErrorCode.WRITE_FAILED);
		}
		// deploy-requests.log 가 아닌 별도 파일: 그 파일 마지막 줄은 배포 버튼의 1분 연타 방지가 읽는다
		append("deploy-approvals.log", now() + " admin#" + adminId + " 승인 " + sha + " 확인한 서버 작업 "
				+ (confirmed == null ? 0 : confirmed.size()) + "개");
		log.info("BLOCKED 승인 (관리자 #{}, 커밋 {})", adminId, sha.substring(0, 7));
		try {
			deploy.trigger(adminId);
		} catch (BusinessException e) {
			// 진행 중·연타 등으로 지금 시작하지 못해도 승인은 남아 있어 다음 자동 주기에 배포된다
			log.info("승인 후 바로 시작하지 못함 → 다음 자동 주기에 배포: {}", e.getMessage());
		}
		return blocked(adminId);
	}

	Item classify(String p, Set<String> applied) {
		if (PATCH.matcher(p).matches()) {
			boolean done = applied.contains(p);
			return new Item(p, Kind.PATCH, done, done ? "적용함" : "[적용]을 눌러 Oracle에 넣습니다 (다시 실행해도 안전하게 쓰는 것이 규칙)");
		}
		if (p.startsWith("docs/erd/") || p.startsWith("db/seed/") || p.endsWith(".md")) {
			return new Item(p, Kind.INFO, true, "서버에 할 일 없음 (문서, 또는 새 DB를 만들 때만 쓰는 시드)");
		}
		return new Item(p, Kind.MANUAL, false, how(p));
	}

	private static String how(String p) {
		String c = "docker compose -f compose.yaml -f deploy/compose.server.yaml";
		if (p.equals("deploy/sudoers-jejari") || p.matches("deploy/jejari[@-][a-z-]*\\.(service|timer)")
				|| p.equals("deploy/jejari@.service")) {
			return "운영자 계정에서 bash deploy/setup-server.sh (systemd 유닛·sudoers 설치, sudo 필요). 승인 배포가 끝난 뒤 해도 됨";
		}
		if (p.equals("deploy/Caddyfile")) {
			return "배포가 끝난 뒤 " + c + " restart caddy";
		}
		if (p.equals("deploy/prometheus.server.yml")) {
			return "배포가 끝난 뒤 " + c + " restart prometheus";
		}
		if (p.startsWith("deploy/grafana-alerting/")) {
			return "배포가 끝난 뒤 " + c + " restart grafana";
		}
		if (p.equals("docker/mongo/mongo-init.js")) {
			return c + " exec -T mongo bash -c 'mongosh --quiet -u \"$MONGO_INITDB_ROOT_USERNAME\" -p \"$MONGO_INITDB_ROOT_PASSWORD\" --authenticationDatabase admin' < docker/mongo/mongo-init.js";
		}
		if (p.equals("compose.yaml") || p.equals("deploy/compose.server.yaml") || p.startsWith("docker/")) {
			return "배포가 끝난 뒤 " + c + " up -d (바뀐 서비스만 다시 만듦)";
		}
		return "deploy/README.md 운영자 안내 참고";
	}

	private record Target(String sha, List<String> paths) {
	}

	/** autodeploy.state 가 BLOCKED 이고 autodeploy.blocked 가 같은 커밋일 때만 */
	private Target target() {
		String st = firstLine(appDir.resolve("autodeploy.state"));
		if (st == null || st.length() < 21) {
			return null;
		}
		String[] parts = st.substring(20).split(" ", 3);
		if (parts.length < 2 || !parts[0].equals("BLOCKED")) {
			return null;
		}
		List<String> lines = readLines(appDir.resolve("autodeploy.blocked"));
		if (lines.isEmpty() || !SHA.matcher(lines.get(0)).matches() || !lines.get(0).startsWith(parts[1])) {
			return null;
		}
		List<String> paths = lines.subList(1, lines.size()).stream().filter(s -> !s.isBlank()).toList();
		return new Target(lines.get(0), paths);
	}

	private Target requireTarget(String sha) {
		if (!enabled) {
			throw new BusinessException(DeployAdminService.DeployErrorCode.DEPLOY_DISABLED);
		}
		Target t = target();
		if (t == null || !t.sha().equals(sha)) {
			throw new BusinessException(ApprovalErrorCode.NOT_BLOCKED);
		}
		return t;
	}

	private void requireAdmin(long adminId) {
		if (!enabled) {
			throw new BusinessException(DeployAdminService.DeployErrorCode.DEPLOY_DISABLED);
		}
		if (!adminIds.contains(adminId)) {
			throw new BusinessException(DeployAdminService.DeployErrorCode.DEPLOY_NOT_ALLOWED);
		}
	}

	/** 성공한 적용 기록이 있는 패치 경로 (패치는 커밋이 달라도 같은 파일이면 한 번이면 됨) */
	private Set<String> appliedPatches() {
		Set<String> s = new java.util.HashSet<>();
		for (String l : readLines(appDir.resolve("db-patches.applied"))) {
			String[] f = l.trim().split(" ");
			// 날짜 시각 커밋 경로 admin#n OK
			if (f.length >= 6 && f[f.length - 1].equals("OK")) {
				s.add(f[3]);
			}
		}
		return s;
	}

	private String approvedAt(String sha) {
		String l = firstLine(appDir.resolve("autodeploy.approved"));
		if (l == null || !l.startsWith(sha + " ")) {
			return null;
		}
		return l.substring(sha.length() + 1);
	}

	private String now() {
		return LocalDateTime.now(clock).format(TS);
	}

	private void append(String name, String line) {
		try {
			Files.writeString(appDir.resolve(name), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.warn("{}에 기록하지 못함: {}", name, e.toString());
		}
	}

	private static String cut(String s) {
		return s.length() > MAX_OUTPUT ? s.substring(0, MAX_OUTPUT) + "\n…(뒤는 생략)" : s;
	}

	private static String firstLine(Path p) {
		List<String> l = readLines(p);
		return l.isEmpty() ? null : l.get(0).strip();
	}

	private static List<String> readLines(Path p) {
		try {
			return Files.readAllLines(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return List.of();
		}
	}

	private static Result exec(List<String> command, String stdin, Map<String, String> env, Duration timeout) {
		try {
			ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
			pb.environment().putAll(env);
			Process p = pb.start();
			try (OutputStream in = p.getOutputStream()) {
				if (stdin != null) {
					in.write(stdin.getBytes(StandardCharsets.UTF_8));
				}
			}
			// 출력을 먼저 다 읽어야 버퍼가 차서 멈추지 않는다 (명령이 끝나면 스트림이 닫힘)
			var reader = new java.util.concurrent.CompletableFuture<String>();
			Thread.ofVirtual().start(() -> {
				try {
					reader.complete(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
				} catch (IOException e) {
					reader.complete("");
				}
			});
			if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
				return new Result(124, "시간 초과 (" + timeout.toSeconds() + "초)");
			}
			return new Result(p.exitValue(), reader.get(5, TimeUnit.SECONDS));
		} catch (IOException e) {
			return new Result(127, e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(130, "중단됨");
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			return new Result(1, e.toString());
		}
	}

	enum ApprovalErrorCode implements ErrorCode {
		NOT_BLOCKED(HttpStatus.CONFLICT, "지금 승인할 BLOCKED 커밋이 아니에요. 화면을 새로 고쳐 주세요."),
		NOT_IN_BLOCKED(HttpStatus.BAD_REQUEST, "막힌 파일 목록에 없는 파일이에요."),
		NOT_A_PATCH(HttpStatus.BAD_REQUEST, "적용할 수 있는 DB 패치(db/patch/vNN.sql)가 아니에요."),
		PATCH_PENDING(HttpStatus.CONFLICT, "아직 적용하지 않은 DB 패치가 있어요."),
		MANUAL_PENDING(HttpStatus.CONFLICT, "서버에서 직접 할 일을 모두 \"처리함\"으로 체크해 주세요."),
		WRITE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "승인 기록을 남기지 못했어요. 서버 디스크·권한을 확인해 주세요.");

		private final HttpStatus status;
		private final String message;

		ApprovalErrorCode(HttpStatus status, String message) {
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
