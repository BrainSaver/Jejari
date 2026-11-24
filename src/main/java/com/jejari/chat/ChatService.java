package com.jejari.chat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.train.TrainQueryService;
import com.jejari.train.TrainQueryService.RunSummary;
import com.jejari.train.TrainQueryService.Station;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 예매 안내 챗봇. <b>안내·조회만</b> 한다: 도구는 역 목록과 열차 검색(읽기)뿐이고, 대기열 진입·좌석 선점·결제는 하지 않는다
 * (공정 예매 흐름을 건드리지 않기 위해). 예매는 화면의 검색 → 대기열로 안내한다.
 *
 * <p>도구는 {@link TrainQueryService}를 직접 부른다. HTTP 컨트롤러를 거치지 않으므로 사용자의 행동 로그(VIEW_TRAIN)에
 * 챗봇 조회가 섞이지 않는다. 도구 결과는 공개 정보(시간표·운임·잔여석)뿐이고 개인정보는 모델에 보내지 않는다.
 */
@Service
public class ChatService {

	static final int MAX_MESSAGES = 12;
	static final int MAX_CHARS = 500;
	static final int MAX_TOOL_ROUNDS = 3;
	static final int MAX_RUNS_IN_RESULT = 8;

	public record Turn(String role, String content) {
	}

	private final ChatModel model;
	private final TrainQueryService trains;
	private final JsonMapper json;
	private final Clock clock;

	public ChatService(ChatModel model, TrainQueryService trains, JsonMapper json, Clock clock) {
		this.model = model;
		this.trains = trains;
		this.json = json;
		this.clock = clock;
	}

	public boolean enabled() {
		return model.enabled();
	}

	public String reply(List<Turn> history) {
		List<Turn> turns = validate(history);
		List<JsonNode> messages = new ArrayList<>();
		messages.add(msg("system", systemPrompt()));
		for (Turn t : turns) {
			messages.add(msg(t.role(), t.content()));
		}
		List<JsonNode> tools = tools();
		for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
			// 마지막 바퀴는 도구 없이 불러 반드시 글로 답하게 한다
			ChatModel.Reply r = model.complete(messages, round == MAX_TOOL_ROUNDS ? List.of() : tools);
			if (!r.wantsTools()) {
				String text = r.content() == null ? "" : plain(r.content());
				return text.isEmpty() ? "잘 모르겠어요. 출발역·도착역·날짜를 알려 주시면 열차를 찾아볼게요." : text;
			}
			messages.add(r.rawMessage());
			for (ChatModel.ToolCall c : r.toolCalls()) {
				ObjectNode toolMsg = json.createObjectNode();
				toolMsg.put("role", "tool");
				toolMsg.put("tool_call_id", c.id());
				toolMsg.put("content", runTool(c.name(), c.argumentsJson()));
				messages.add(toolMsg);
			}
		}
		return "조회가 길어졌어요. 다시 한 번 물어봐 주세요.";
	}

	private List<Turn> validate(List<Turn> history) {
		if (history == null || history.isEmpty() || history.size() > MAX_MESSAGES) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "대화는 1~" + MAX_MESSAGES + "개 메시지여야 합니다.");
		}
		List<Turn> out = new ArrayList<>();
		for (Turn t : history) {
			// system·tool 역할은 받지 않는다 (사용자가 지시문을 끼워 넣지 못하게)
			if (t == null || t.content() == null || t.content().isBlank()
					|| !("user".equals(t.role()) || "assistant".equals(t.role()))) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "메시지 형식이 올바르지 않습니다.");
			}
			if (t.content().length() > MAX_CHARS) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "한 번에 " + MAX_CHARS + "자까지 보낼 수 있어요.");
			}
			out.add(t);
		}
		if (!"user".equals(out.get(out.size() - 1).role())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "마지막 메시지는 사용자 질문이어야 합니다.");
		}
		return out;
	}

	String systemPrompt() {
		return """
				너는 명절 열차 예매 서비스 '제자리'의 안내 도우미야. 한국어로 짧고 친절하게 답해.
				오늘은 %s 이야.
				할 수 있는 일: 열차 시간표·운임·잔여석 조회, 예매 방법과 흐름 안내.
				할 수 없는 일: 예매·결제·취소를 대신하는 것. 사용자가 예매하려 하면 화면의 '검색'에서 열차를 골라 대기열을 거쳐 진행하라고 안내해.
				열차 정보는 반드시 search_runs 도구 결과만 근거로 말하고, 없는 열차·시각·가격을 지어내지 마.
				시각 조건("12시 이후", "오전" 등)이 있으면 depart_after·depart_before로 조회해. 조건에 안 맞는 열차를 나열하지 마. 결과에 안내가 있을 때만 일부만 보여 준 것이라고 말하고, 없으면 전부 보여 준 것이니 일부라고 말하지 마. 도구·옵션 이름(search_runs, depart_after 등)은 사용자에게 말하지 마.
				열차 번호는 결과에 나온 그대로(예: 00103) 써.
				역 이름이 불확실하면 list_stations로 확인해. 날짜가 없으면 사용자에게 물어봐.
				개인정보(주민번호·카드번호·비밀번호)는 받지도 말하지도 말고, 입력하지 말라고 안내해.
				예매·열차와 무관한 요청은 정중히 거절해.
				답은 마크다운(표, **굵게**, 제목, 이모지)을 쓰지 말고 평범한 글로, 열차는 한 줄씩 "09:28 → 12:35 KTX 103 · 일반 54,400원 · 잔여 495석"처럼 써.
				""".formatted(LocalDate.now(clock));
	}

	private List<JsonNode> tools() {
		return List.of(tool("list_stations", "예매 가능한 역 이름 목록을 돌려준다.", json.createObjectNode().put("type", "object")),
				tool("search_runs", "출발역→도착역, 날짜의 열차 시간표·운임·잔여석을 조회한다.", searchSchema()));
	}

	private ObjectNode searchSchema() {
		ObjectNode props = json.createObjectNode();
		props.set("from", json.createObjectNode().put("type", "string").put("description", "출발역 이름 (예: 서울)"));
		props.set("to", json.createObjectNode().put("type", "string").put("description", "도착역 이름 (예: 부산)"));
		props.set("date", json.createObjectNode().put("type", "string").put("description", "탑승일 YYYY-MM-DD"));
		props.set("depart_after", json.createObjectNode().put("type", "string")
				.put("description", "이 시각 이후(포함)에 출발하는 열차만, HH:mm 24시간 (예: 오후 3시 이후면 15:00). 시각 조건이 없으면 생략"));
		props.set("depart_before", json.createObjectNode().put("type", "string")
				.put("description", "이 시각 이전(포함)에 출발하는 열차만, HH:mm 24시간. 시각 조건이 없으면 생략"));
		ObjectNode s = json.createObjectNode().put("type", "object");
		s.set("properties", props);
		s.set("required", json.valueToTree(List.of("from", "to", "date")));
		return s;
	}

	private ObjectNode tool(String name, String description, ObjectNode params) {
		ObjectNode fn = json.createObjectNode().put("name", name).put("description", description);
		fn.set("parameters", params);
		ObjectNode t = json.createObjectNode().put("type", "function");
		t.set("function", fn);
		return t;
	}

	private ObjectNode msg(String role, String content) {
		return json.createObjectNode().put("role", role).put("content", content);
	}

	/** 도구 실행. 오류도 모델이 읽고 사용자에게 설명할 수 있게 글로 돌려준다 */
	String runTool(String name, String argsJson) {
		try {
			JsonNode a = json.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
			return switch (name) {
				case "list_stations" -> json.writeValueAsString(
						trains.stations().stream().map(Station::stationName).toList());
				case "search_runs" -> searchRuns(a.path("from").asString(""), a.path("to").asString(""),
						a.path("date").asString(""), a.path("depart_after").asString(""), a.path("depart_before").asString(""));
				default -> "알 수 없는 도구입니다.";
			};
		} catch (Exception e) {
			return "도구 실행에 실패했습니다.";
		}
	}

	private String searchRuns(String from, String to, String date, String after, String before) throws Exception {
		List<Station> all = trains.stations();
		Station f = find(all, from);
		Station t = find(all, to);
		if (f == null || t == null) {
			return "역을 찾지 못했습니다: " + (f == null ? from : to) + ". list_stations로 이름을 확인하세요.";
		}
		LocalDate day;
		try {
			day = LocalDate.parse(date);
		} catch (DateTimeParseException e) {
			return "날짜는 YYYY-MM-DD 형식이어야 합니다.";
		}
		LocalTime from0 = parseTime(after);
		LocalTime to0 = parseTime(before);
		if ((!after.isBlank() && from0 == null) || (!before.isBlank() && to0 == null)) {
			return "시각은 HH:mm 형식이어야 합니다 (예: 15:00).";
		}
		// 시각 조건은 앞 N건을 자르기 전에 건다: 안 그러면 이른 열차 N건만 보고 "그 시각 이후"라고 답하게 된다
		List<RunSummary> runs = trains.search(f.stationId(), t.stationId(), day).stream()
				.filter(r -> r.departureAt() != null
						&& (from0 == null || !r.departureAt().toLocalTime().isBefore(from0))
						&& (to0 == null || !r.departureAt().toLocalTime().isAfter(to0)))
				.toList();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (RunSummary r : runs.stream().limit(MAX_RUNS_IN_RESULT).toList()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("열차", r.trainKind() + " " + r.trainNo());
			m.put("출발", hm(r.departureAt()));
			m.put("도착", hm(r.arrivalAt()));
			m.put("소요분", r.durationMin());
			m.put("판매상태", r.saleStatus());
			m.put("예매가능", r.bookable());
			if (r.saleOpenAt() != null && !r.bookable()) {
				m.put("판매시작", r.saleOpenAt().toString());
			}
			// 비어 있는 값을 그대로 주면 모델이 "0원·0석"이라고 말한다
			m.put("운임(원)", r.fares() == null || r.fares().isEmpty() ? "정보 없음" : r.fares());
			m.put("잔여석", r.remaining() == null || r.remaining().isEmpty() ? "정보 없음" : r.remaining());
			rows.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("조회", f.stationName() + " → " + t.stationName() + " " + day);
		out.put("조건", (from0 == null ? "" : from0 + " 이후 ") + (to0 == null ? "" : to0 + " 이전 ") + "출발");
		out.put("전체건수", runs.size());
		out.put("표시건수", rows.size());
		if (runs.size() > rows.size()) {
			out.put("안내", "출발 시각순으로 앞의 " + rows.size() + "건만 보여 준 것입니다. 사용자에게는 '나머지는 원하는 시간대를 알려 주시면 찾아볼게요'라고 안내하고, 도구 이름이나 옵션 이름은 말하지 마세요.");
		}
		out.put("열차", rows);
		return json.writeValueAsString(out);
	}

	private static LocalTime parseTime(String t) {
		try {
			return t == null || t.isBlank() ? null : LocalTime.parse(t.strip());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** 정확히 같거나, "서울역"처럼 '역'이 붙어도 찾는다 */
	static Station find(List<Station> all, String name) {
		String n = name == null ? "" : name.strip();
		String bare = n.endsWith("역") ? n.substring(0, n.length() - 1) : n;
		if (bare.isEmpty()) {
			return null;
		}
		for (Station s : all) {
			String sn = s.stationName();
			String sb = sn.endsWith("역") ? sn.substring(0, sn.length() - 1) : sn;
			if (sn.equals(n) || sb.equals(bare)) {
				return s;
			}
		}
		return null;
	}

	/** 채팅창은 글자 그대로 보여 준다. 프롬프트로 금지해도 모델이 가끔 쓰는 굵게(**)·코드(`)·제목(#) 표시를 지운다 */
	static String plain(String s) {
		return s.replaceAll("\\*\\*|`", "").replaceAll("(?m)^#{1,6}\\s+", "").strip();
	}

	private static String hm(LocalDateTime t) {
		return t == null ? "" : String.format("%02d:%02d", t.getHour(), t.getMinute());
	}
}
