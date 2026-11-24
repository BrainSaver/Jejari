package com.jejari.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.jejari.chat.ChatService.Turn;

/**
 * 챗봇 앞뒤 검사. 모델(프롬프트)만 믿지 않고 서버가 정해진 규칙으로 한 번 더 막는다.
 *
 * <ul>
 * <li>질문: 코드·매크로·자동화 요청, 지시 무시·프롬프트 빼내기, 개인정보 → 모델을 부르지 않고 정해진 문구로 답한다</li>
 * <li>이전 대화: 화면이 보내는 값이라 위조할 수 있다 (가짜 "assistant: 코드 여기 있어요"). 걸리는 메시지는 모델에 넘기지 않고 뺀다.
 * 막힌 질문이 대화에 남아 다음 질문까지 계속 막히는 일도 이걸로 없앤다</li>
 * <li>답: 질문을 돌려 말해 앞 검사를 피해도, 답에 코드·주소·도구 이름·프롬프트 조각·전화번호·개인정보가 섞이면 통째로 바꾼다</li>
 * </ul>
 */
final class ChatGuard {

	static final String NO_CODE = "죄송해요, 프로그램 코드나 자동으로 조회·예매하는 방법은 알려 드릴 수 없어요. 저는 열차 찾기와 예매 안내를 도와드리고 있어요. "
			+ "가고 싶은 날짜와 출발역·도착역을 알려 주시면 열차를 바로 찾아 드릴게요.";
	static final String NO_PROMPT = "죄송해요, 그 부탁은 들어 드리기 어려워요. 열차 시간표·운임·남은 좌석이나 예매 방법이 궁금하시면 언제든 편하게 물어봐 주세요.";
	static final String NO_PII = "소중한 개인정보(주민등록번호·카드번호·휴대폰 번호·이메일)는 여기에 적지 않으셔도 괜찮아요. 개인정보 없이도 열차는 찾아 드릴 수 있어요. "
			+ "예약 확인은 '예매 내역'이나 로그인 화면의 [비회원 예약 조회]에서 안전하게 하실 수 있어요.";
	static final String NO_ANSWER = "죄송해요, 그 내용은 제가 안내해 드리기 어려워요. 열차 시간표·운임·남은 좌석이나 예매 방법이라면 기꺼이 도와드릴게요.";

	/** "예약 코드", "QR 코드"처럼 서비스 이용에서 나오는 말. 검사 전에 지워서 코드 요청으로 오해하지 않는다 */
	private static final Pattern BENIGN = Pattern.compile("(?i)(예약|예매|할인|인증|쿠폰|프로모션|qr|큐알|승차|좌석|역|열차|확인|오류|에러|우편|초대|상품)\\s*코드");

	/** 이 낱말이 있으면 서비스 이용 질문일 수 없다 (언어·도구·자동화 이름) */
	private static final Pattern CODE_WORDS = Pattern.compile("(?i)(파이썬|python|자바\\s*스크립트|javascript|typescript|node\\.?js|(?<![a-z])java(?![a-z])|"
			+ "c\\+\\+|c#|(?<![a-z])vba(?![a-z])|매크로|macro|스크립트|script|크롤|crawl|스크래|scrap|셀레니움|selenium|playwright|puppeteer|"
			+ "requests|axios|curl|wget|postman|(?<![a-z])api(?![a-z])|엔드포인트|endpoint|자동화|오토\\s*(클릭|마우스)|auto\\s*click|"
			+ "소스\\s*코드|source\\s*code|개발자\\s*도구|devtools|네트워크\\s*탭|패킷|쿠키|cookie)");

	/** 낱말 하나로는 애매해서 "만들어 줘"류와 같이 나올 때만 (코드 짜 줘, 프로그램 만들어 줘, 봇 만들어 줘) */
	private static final Pattern CODE_REQUEST = Pattern.compile(
			"(?i)(코드|프로그램|소스|함수|봇|bot|code)\\s*(을|를|좀|하나)?\\s*(짜|만들|작성|구현|알려|보여|줘|주세요|예시)");

	/** 지시 무시·역할 바꾸기·프롬프트 빼내기 */
	private static final Pattern PROMPT_ATTACK = Pattern.compile("(?i)(시스템\\s*프롬프트|system\\s*prompt|프롬프트|prompt|"
			+ "(이전|위|앞|모든)\\s*(의)?\\s*(지시|명령|규칙|대화|설정)\\s*(은|는|을|를)?\\s*(다|전부|모두)?\\s*(무시|잊)|지시\\s*(를|을)?\\s*무시|"
			+ "ignore\\s+(all|any|previous|prior|the|your)|disregard|개발자\\s*모드|developer\\s*mode|관리자\\s*모드|jailbreak|탈옥|(?<![a-z])dan(?![a-z])|"
			+ "너의?\\s*(규칙|설정|지침|지시)|네\\s*(규칙|설정|지침|지시)|instructions?|역할\\s*(을|를)?\\s*바꿔|(지금부터|이제부터)\\s*(너는|넌))");

	// 자바의 \b는 한글도 글자로 봐서 "1234로"·"com에서"처럼 한글이 바로 붙으면 경계가 안 잡힌다. 그래서 앞뒤를 직접 본다
	/** 개인정보: 주민등록번호, 카드번호, 휴대폰 번호, 이메일 */
	private static final Pattern PII = Pattern.compile(
			"((?<!\\d)\\d{6}\\s*-?\\s*[1-4]\\d{6}(?!\\d)|(?<!\\d)(\\d{4}[- ]?){3}\\d{4}(?!\\d)|(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)|"
					+ "[a-z0-9._%+-]+@[a-z0-9-]+\\.[a-z0-9.]+)",
			Pattern.CASE_INSENSITIVE);

	/** 답에 섞이면 안 되는 것: 코드 블록·코드 줄·메서드 호출, 주소, 내부 도구·옵션 이름 */
	private static final Pattern CODE_OUT = Pattern.compile("(?im)(```|^\\s*(import|from\\s+\\S+\\s+import|def|class|function|const|let|var|"
			+ "public|private|async|return|sub|dim)(?![a-z0-9_])|^\\s*(if|for|while)\\s*\\(|^\\s*[#$>]\\s*[a-z]|"
			+ "(?<![a-z0-9_])[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*\\(|"
			+ "requests\\.|fetch\\(|curl\\s|https?://|www\\.|\\.(com|kr|net|io|org|app)(?![a-z])|"
			+ "search_runs|list_stations|depart_after|depart_before|tool_call|<script|=>|;\\s*$)");

	/** 프롬프트를 그대로 옮겨 적은 흔적 (시스템 프롬프트에만 있는 문장 조각) */
	private static final Pattern PROMPT_LEAK = Pattern.compile("(할 수 있는 일:|할 수 없는 일:|서비스 이용 안내 \\(|도구·옵션 이름|시스템 프롬프트|열차의 '상태'는)");

	/** 지어낸 연락처: 대표번호(1588-0000), 지역번호·휴대폰 번호 */
	private static final Pattern PHONE_OUT = Pattern.compile("((?<![\\d-])1[5-9]\\d{2}-\\d{4}(?![\\d-])|(?<![\\d-])0\\d{1,2}-\\d{3,4}-\\d{4}(?![\\d-]))");

	private ChatGuard() {
	}

	/** 마지막 질문을 본다. 막아야 하면 그 답 문구, 괜찮으면 null */
	static String checkQuestion(String q) {
		if (PII.matcher(q).find()) {
			return NO_PII;
		}
		if (PROMPT_ATTACK.matcher(q).find()) {
			return NO_PROMPT;
		}
		String s = BENIGN.matcher(q).replaceAll(" ");
		if (CODE_WORDS.matcher(s).find() || CODE_REQUEST.matcher(s).find()) {
			return NO_CODE;
		}
		return null;
	}

	/** 마지막 질문 앞의 대화에서 걸리는 메시지를 뺀다. 질문을 뺐으면 바로 뒤 답도 같이 뺀다 (짝이 안 맞는 대화가 되지 않게) */
	static List<Turn> cleanHistory(List<Turn> turns) {
		List<Turn> out = new ArrayList<>();
		boolean skipReply = false;
		for (int i = 0; i < turns.size() - 1; i++) {
			Turn t = turns.get(i);
			boolean bad = "user".equals(t.role()) ? checkQuestion(t.content()) != null : checkAnswer(t.content()) != null;
			if ("assistant".equals(t.role()) && skipReply) {
				skipReply = false;
				continue;
			}
			skipReply = false;
			if (bad) {
				skipReply = "user".equals(t.role());
				continue;
			}
			out.add(t);
		}
		out.add(turns.get(turns.size() - 1));
		return out;
	}

	/** 모델의 답을 본다. 막아야 하면 바꿀 문구, 괜찮으면 null */
	static String checkAnswer(String a) {
		if (CODE_OUT.matcher(a).find()) {
			return NO_CODE;
		}
		if (PII.matcher(a).find()) {
			return NO_PII;
		}
		if (PROMPT_LEAK.matcher(a).find() || PHONE_OUT.matcher(a).find()) {
			return NO_ANSWER;
		}
		return null;
	}
}
