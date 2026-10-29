package com.Hotel.Module_Report.exception;

import com.Hotel.Module_Report.controller.ReportController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ReportController.class)
public class ReportExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ReportErrorResponse> business(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ReportErrorResponse(ex.getStatusCode().value(), ex.getReason()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ReportErrorResponse> missing(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(new ReportErrorResponse(400,
                "Missing required parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ReportErrorResponse> invalid(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ReportErrorResponse(400,
                "Invalid parameter: " + ex.getName() + "; dates use yyyy-MM-dd, groupBy uses DAY, WEEK or MONTH"));
    }
}
