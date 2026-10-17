package com.aloute.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

/** Forces Spring Security's deferred CSRF token to be materialized on safe page requests. */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute == null) attribute = request.getAttribute("_csrf");

        if (attribute instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        } else if (attribute instanceof Supplier<?> supplier) {
            Object supplied = supplier.get();
            if (supplied instanceof CsrfToken csrfToken) csrfToken.getToken();
        }

        filterChain.doFilter(request, response);
    }
}
