package com.jejari.queue.domain;

import org.springframework.http.HttpStatus;

/** 기능설계서 9장 에러 코드 중 대기열이 쓰는 것. [가정] 표시는 설계서에 없던 코드. */
public enum ErrorCode {
    RUN_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 운행이 아닙니다"),
    QUEUE_DUPLICATED(HttpStatus.CONFLICT, "이미 다른 열차를 기다리고 있거나, 같은 본인인증의 다른 계정이 대기 중입니다"),
    QUEUE_TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "입장 토큰이 필요합니다"),
    QUEUE_TOKEN_INVALID(HttpStatus.FORBIDDEN, "입장 토큰이 만료됐거나 맞지 않습니다"),
    AUTH_BANNED(HttpStatus.FORBIDDEN, "이용이 정지된 계정입니다"),
    /** [가정] 설계서에 없음: 대기 중이 아닌데 번호표를 조회함 */
    QUEUE_NOT_FOUND(HttpStatus.NOT_FOUND, "대기 중이 아닙니다"),
    /** [가정] 설계서에 없음: 로그인 안 한 요청. E의 JWT 인증이 붙으면 그쪽 코드를 따른다 */
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    /** [가정] 설계서에 없음: Redis에 연결할 수 없거나 응답이 늦음. 오래 붙잡지 않고 바로 알린다 */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "대기열을 잠시 쓸 수 없습니다. 잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
