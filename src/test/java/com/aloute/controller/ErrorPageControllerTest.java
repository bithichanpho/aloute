package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

class ErrorPageControllerTest {
    private final ErrorPageController controller = new ErrorPageController(JsonMapper.builder().build());

    @Test
    void browser404ReturnsCustomThymeleafView() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/not-a-page");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/not-a-page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView result = controller.error(request, response);
        assertEquals("error/404", result.getViewName());
        assertEquals(HttpStatus.NOT_FOUND, result.getStatus());
    }

    @Test
    void api404WritesJsonInsteadOfTryingToRenderHtml() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/missing");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView result = controller.error(request, response);
        assertNull(result);
        assertEquals(404, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("Không tìm thấy tài nguyên"));
    }
}
