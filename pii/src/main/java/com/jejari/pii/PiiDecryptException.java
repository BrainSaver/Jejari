package com.jejari.pii;

/**
 * 복호화 실패: 암호문 변조, 키 오류, AAD(테이블.컬럼) 불일치, 형식 오류.
 * 메시지에 암호문·평문·키를 절대 넣지 않는다.
 */
public class PiiDecryptException extends RuntimeException {

    public PiiDecryptException(String message) {
        super(message);
    }

    public PiiDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
