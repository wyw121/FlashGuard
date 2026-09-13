package com.flashguard.shared.observability;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String REQUEST_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveRequestId(TRACE_HEADER, request);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString();
        }
        CorrelationContext.setTraceId(traceId);
        response.setHeader(TRACE_HEADER, traceId);

        Optional.ofNullable(resolveRequestId(REQUEST_HEADER, request))
            .filter(StringUtils::hasText)
            .ifPresent(CorrelationContext::setRequestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clearAll();
        }
    }

    private String resolveRequestId(String headerName, HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(headerName))
            .filter(StringUtils::hasText)
            .orElseGet(() -> {
                String queryValue = request.getParameter(headerName);
                return queryValue != null ? queryValue : request.getParameter("requestId");
            });
    }
}
