package com.jejari.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

import com.jejari.auth.AuthErrorCode;

/**
 * 컨트롤러에서 나온 예외를 { code, message, traceId }로 바꿉니다.
 * 필터 단계(인증 실패 등)의 응답은 {@link ErrorResponseWriter}가 같은 형식으로 씁니다.
 *
 * <p>입력값(rejected value)은 응답·로그에 넣지 않습니다. 개인정보가 섞일 수 있기 때문입니다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		ErrorCode code = e.getErrorCode();
		if (code.status().is5xxServerError()) {
			log.error("business error {}", code.code(), e);
		} else {
			log.debug("business error {}: {}", code.code(), e.getMessage());
		}
		if (e instanceof RetryAfterException r) {
			return ResponseEntity.status(code.status()).header(org.springframework.http.HttpHeaders.RETRY_AFTER,
					String.valueOf(r.retryAfterSec())).body(ErrorResponse.of(code, e.getMessage()));
		}
		return respond(code, e.getMessage());
	}

	/** @Valid 본문 검증 실패. 첫 번째 필드 오류만 알려줍니다. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
		FieldError fe = e.getBindingResult().getFieldError();
		String message = fe == null
				? CommonErrorCode.INVALID_REQUEST.message()
				: fe.getField() + ": " + fe.getDefaultMessage();
		return respond(CommonErrorCode.INVALID_REQUEST, message);
	}

	/** @RequestParam·@PathVariable 등 메서드 파라미터 검증 실패. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleInvalidParam(HandlerMethodValidationException e) {
		String message = e.getParameterValidationResults().stream()
				.flatMap(r -> r.getResolvableErrors().stream()
						.map(err -> r.getMethodParameter().getParameterName() + ": " + err.getDefaultMessage()))
				.findFirst()
				.orElse(CommonErrorCode.INVALID_REQUEST.message());
		return respond(CommonErrorCode.INVALID_REQUEST, message);
	}

	@ExceptionHandler({
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class,
			ServletRequestBindingException.class // 필수 헤더(Idempotency-Key 등) 누락 포함
	})
	public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
		log.debug("bad request: {}", e.getClass().getSimpleName());
		return respond(CommonErrorCode.INVALID_REQUEST, CommonErrorCode.INVALID_REQUEST.message());
	}

	/** 빌드된 화면. 테스트처럼 프론트엔드 빌드가 없으면 JSON 404로 답한다 */
	private static final ClassPathResource SPA_INDEX = new ClassPathResource("static/index.html");

	/**
	 * 없는 경로. 브라우저가 화면을 열려던 것(GET·HEAD, Accept에 text/html, 파일 확장자 없음, API 아님)이면
	 * 상태는 404 그대로 두고 화면(index.html)을 돌려줘 "없는 페이지예요" 안내를 보여 준다. 그 밖(API, 없는 정적 파일)은 JSON 404.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<?> handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
		if (isBrowserPage(request) && SPA_INDEX.exists()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.contentType(MediaType.TEXT_HTML)
					.cacheControl(CacheControl.noStore())
					.body(SPA_INDEX);
		}
		return respond(CommonErrorCode.NOT_FOUND, CommonErrorCode.NOT_FOUND.message());
	}

	static boolean isBrowserPage(HttpServletRequest request) {
		String method = request.getMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			return false;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (path.startsWith("/api/") || path.startsWith("/actuator/")) {
			return false;
		}
		String last = path.substring(path.lastIndexOf('/') + 1);
		if (last.contains(".")) {
			return false;
		}
		String accept = request.getHeader(HttpHeaders.ACCEPT);
		return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
		return respond(CommonErrorCode.METHOD_NOT_ALLOWED, CommonErrorCode.METHOD_NOT_ALLOWED.message());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
		return respond(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.message());
	}

	/**
	 * @PreAuthorize 등 메서드 보안 거부 (예: VIEWER가 REVIEWER 전용 API 호출). URL 규칙 거부와 같은 코드로 응답합니다.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
		ErrorCode code = request.getRequestURI().startsWith("/api/v1/admin/")
				? AuthErrorCode.ADMIN_FORBIDDEN
				: CommonErrorCode.ACCESS_DENIED;
		return respond(code, code.message());
	}

	/** 예상하지 못한 오류. 내부 메시지는 응답에 넣지 않고 로그에만 남깁니다 (traceId로 찾음). */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("unexpected error", e);
		return respond(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.message());
	}

	private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
	}
}
