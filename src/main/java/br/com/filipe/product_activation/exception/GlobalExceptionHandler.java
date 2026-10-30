package br.com.filipe.product_activation.exception;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Dados invalidos",
                "Um ou mais campos nao passaram na validacao.");
        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(InvalidActivationRequestException.class)
    public ProblemDetail handleInvalidActivationRequest(InvalidActivationRequestException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Dados invalidos",
                "Um ou mais campos nao passaram na validacao.");
        problem.setProperty("errors", List.of(new FieldErrorDetail(ex.getField(), ex.getMessage())));
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Dados invalidos",
                "Um ou mais parametros nao passaram na validacao.");
        List<FieldErrorDetail> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    public ProblemDetail handleBadRequest(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Requisicao invalida",
                "Corpo ou parametros da requisicao invalidos.");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ProblemDetail handleRequestNotPermitted(RequestNotPermitted ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Limite de requisicoes excedido",
                "Muitas requisicoes em um curto periodo. Tente novamente mais tarde.");
    }

    @ExceptionHandler(ActivationNotFoundException.class)
    public ProblemDetail handleNotFound(ActivationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Solicitacao nao encontrada", ex.getMessage());
    }

    @ExceptionHandler(DuplicateActivationException.class)
    public ProblemDetail handleDuplicate(DuplicateActivationException ex) {
        return problem(HttpStatus.CONFLICT, "Solicitacao duplicada", ex.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidStatusTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Transicao de status invalida", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente mais tarde.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://api.product-activation.local/problems/" + status.value()));
        String instance = MDC.get("instance");
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    public record FieldErrorDetail(String field, String message) {
    }
}
