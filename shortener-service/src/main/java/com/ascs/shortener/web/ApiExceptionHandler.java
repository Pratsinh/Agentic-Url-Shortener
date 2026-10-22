package com.ascs.shortener.web;

import com.ascs.shortener.domain.ShortenerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

/**
 * Every error leaves the API as an RFC 7807 problem response:
 * <ul>
 *   <li>domain exceptions map to 400/404/410/503;</li>
 *   <li>Spring MVC exceptions (malformed JSON, wrong method, unknown path, ...) are handled by the
 *       {@link ResponseEntityExceptionHandler} base class;</li>
 *   <li>anything else is a 500 that reveals no internals, only a reference id that is logged with the stack trace.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ShortenerException.class)
    ProblemDetail handle(ShortenerException e) {
        HttpStatus status = switch (e) {
            case ShortenerException.InvalidRequest ignored -> HttpStatus.BAD_REQUEST;
            case ShortenerException.NotFound ignored -> HttpStatus.NOT_FOUND;
            case ShortenerException.Expired ignored -> HttpStatus.GONE;
            case ShortenerException.CodeExhausted ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            case ShortenerException.StorageUnavailable ignored -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        if (status.is5xxServerError()) log.warn("request failed: {} {}", status.value(), e.getMessage(), e.getCause());
        else log.debug("request rejected: {} {}", status.value(), e.getMessage());
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        String ref = UUID.randomUUID().toString();
        log.error("unhandled error ref={}", ref, e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "unexpected error; quote reference " + ref);
        problem.setProperty("errorRef", ref);
        return problem;
    }
}
