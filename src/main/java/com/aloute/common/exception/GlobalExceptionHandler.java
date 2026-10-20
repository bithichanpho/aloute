package com.aloute.common.exception;

import com.aloute.common.response.ApiResponse;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.ModelAndView;

import jakarta.validation.ConstraintViolationException;

import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final JsonMapper objectMapper;

    public GlobalExceptionHandler(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ModelAndView handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isHtmlPageRequest(request)) return errorView("error/404", HttpStatus.NOT_FOUND);
        writeJsonError(response, HttpStatus.NOT_FOUND, ex.getMessage());
        return null;
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ModelAndView handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        return clientError(request, response, HttpStatus.NOT_FOUND, "error/404", "Không tìm thấy tài nguyên");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ModelAndView handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            response.setHeader("Allow", ex.getSupportedHttpMethods().stream()
                    .map(org.springframework.http.HttpMethod::name)
                    .collect(Collectors.joining(", ")));
        }
        return clientError(request, response, HttpStatus.METHOD_NOT_ALLOWED, "error/404",
                "Phương thức HTTP không được hỗ trợ cho tài nguyên này");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ModelAndView handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()) {
            response.setHeader("Accept", ex.getSupportedMediaTypes().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ")));
        }
        return clientError(request, response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error/404",
                "Content-Type của yêu cầu không được hỗ trợ");
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ModelAndView handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        log.debug("Access denied for request path={}", request.getRequestURI());
        return clientError(request, response, HttpStatus.FORBIDDEN, "error/403",
                "Bạn không có quyền thực hiện thao tác này");
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateKey(
            org.springframework.dao.DuplicateKeyException ex) {
        log.debug("Request conflicted with an existing unique record ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Dữ liệu đã tồn tại hoặc bị trùng lặp"));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLockingFailure(
            org.springframework.dao.OptimisticLockingFailureException ex) {
        log.info("Optimistic locking conflict; client should reload and retry the operation");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Dữ liệu vừa được thay đổi. Vui lòng tải lại và thử lại."));
    }

    private ModelAndView clientError(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String viewName,
            String message) throws IOException {
        if (isHtmlPageRequest(request)) return errorView(viewName, status);
        writeJsonError(response, status, message);
        return null;
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ModelAndView handleForbidden(
            ForbiddenException ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isHtmlPageRequest(request)) return errorView("error/403", HttpStatus.FORBIDDEN);
        writeJsonError(response, HttpStatus.FORBIDDEN, ex.getMessage());
        return null;
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        NumberFormatException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleInvalidInput(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Dữ liệu gửi lên không hợp lệ"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        // The underlying exception can include multipart/temp-file details; do not expose it.
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiResponse.error("Tệp tải lên vượt quá dung lượng cho phép"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        String message =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(FieldError::getDefaultMessage)
                        .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Object>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus effective = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        String reason = ex.getReason() == null ? "Yêu cầu không thể xử lý lúc này" : ex.getReason();
        if (effective.is5xxServerError()) {
            log.warn("Request temporarily unavailable: status={}, reason={}", effective.value(), reason);
        }
        return ResponseEntity.status(effective).body(ApiResponse.error(reason));
    }

    @ExceptionHandler(EmailDeliveryException.class)
    public ResponseEntity<ApiResponse<Object>> handleEmailDelivery(EmailDeliveryException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Object>> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<?> handleNotWritable(
            HttpMessageNotWritableException ex, HttpServletRequest request) {
        if (isRealtimeTransport(request)) {
            log.debug("SockJS transport ended while writing response: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        log.error("Khong the ghi HTTP response: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error("Đã có lỗi xảy ra, vui lòng thử lại sau"));
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleGeneral(
            Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isRealtimeTransport(request)) {
            log.debug("Realtime transport request ended: {}", ex.getMessage());
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }
        if (isHtmlPageRequest(request)) {
            log.error("Lỗi xử lý trang HTML (path={})", request.getRequestURI(), ex);
            return errorView("error/500", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        log.error("Lỗi không xác định khi xử lý request", ex);
        writeJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR,
                "Đã có lỗi xảy ra, vui lòng thử lại sau");
        return null;
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }

    private ModelAndView errorView(String viewName, HttpStatus status) {
        ModelAndView model = new ModelAndView(viewName);
        model.setStatus(status);
        model.addObject("statusCode", status.value());
        return model;
    }

    private boolean isHtmlPageRequest(HttpServletRequest request) {
        if (request == null) return false;
        String path = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return path != null && !path.startsWith("/api/") && !path.equals("/api")
                && (accept == null || accept.contains("text/html") || accept.contains("*/*"));
    }

    private boolean isRealtimeTransport(HttpServletRequest request) {
        if (request == null) return false;
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return (uri != null && (uri.equals("/ws") || uri.startsWith("/ws/")))
                || (accept != null && accept.contains("text/event-stream"));
    }
}
