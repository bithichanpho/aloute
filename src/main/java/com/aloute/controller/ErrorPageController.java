package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/** Renders branded HTML error pages while preserving JSON responses for API errors. */
@Controller
public class ErrorPageController implements ErrorController {
    private final JsonMapper objectMapper;

    public ErrorPageController(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @RequestMapping("/error")
    public ModelAndView error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = statusAttribute instanceof Integer value ? value : 500;
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = originalPath == null ? request.getRequestURI() : String.valueOf(originalPath);
        boolean api = path != null && (path.equals("/api") || path.startsWith("/api/"));
        response.setStatus(status.value());
        if (api) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(
                    status == HttpStatus.NOT_FOUND ? "Không tìm thấy tài nguyên"
                            : status == HttpStatus.FORBIDDEN ? "Bạn không có quyền truy cập"
                            : "Đã có lỗi xảy ra, vui lòng thử lại sau"));
            return null;
        }
        String view = status == HttpStatus.NOT_FOUND ? "error/404"
                : status == HttpStatus.FORBIDDEN ? "error/403" : "error/500";
        ModelAndView model = new ModelAndView(view);
        model.setStatus(status);
        model.addObject("statusCode", status.value());
        return model;
    }
}
