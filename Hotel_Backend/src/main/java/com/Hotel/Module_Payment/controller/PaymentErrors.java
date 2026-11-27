package com.Hotel.Module_Payment.controller;
import com.Hotel.Module_Payment.entity.PaymentException;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses=PaymentController.class)
public class PaymentErrors {
    private ResponseEntity<?> error(int status,String code,String message) { return ResponseEntity.status(status).body(Map.of("code",code,"message",message)); }
    @ExceptionHandler(PaymentException.class) public ResponseEntity<?> domain(PaymentException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler(StayException.class) public ResponseEntity<?> ledger(StayException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception e) { return error(400,"INVALID_REQUEST","Invalid payment request"); }
    @ExceptionHandler({DataIntegrityViolationException.class,ConcurrencyFailureException.class})
    public ResponseEntity<?> conflict(Exception e) { return error(409,"CONCURRENT_CHANGE","Payment data changed; reload before retrying"); }
}
