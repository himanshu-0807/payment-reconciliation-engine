package dev.recon.service.api;

import dev.recon.service.engine.ReconEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return ApiErrors.validation(req.getRequestURI(), errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalid(ConstraintViolationException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage())).toList();
        return ApiErrors.validation(req.getRequestURI(), errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(Exception e, HttpServletRequest req) {
        String msg = e instanceof HttpMessageNotReadableException ? "malformed JSON body" : e.getMessage();
        return ApiErrors.problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg, req.getRequestURI());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noRoute(NoResourceFoundException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such endpoint", req.getRequestURI());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.CONFLICT, e.code(), e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(ReconEngine.RunInProgressException.class)
    ProblemDetail busy(ReconEngine.RunInProgressException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.CONFLICT, "RUN_IN_PROGRESS", e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail stale(OptimisticLockingFailureException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "the record was changed by someone else; reload and retry", req.getRequestURI());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail badLogin(BadCredentialsException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid username or password", req.getRequestURI());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "your role does not allow this action", req.getRequestURI());
    }

    @ExceptionHandler(UpstreamException.class)
    ProblemDetail upstream(UpstreamException e, HttpServletRequest req) {
        return ApiErrors.problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled error on {}", req.getRequestURI(), e);
        return ApiErrors.problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error", req.getRequestURI());
    }
}
