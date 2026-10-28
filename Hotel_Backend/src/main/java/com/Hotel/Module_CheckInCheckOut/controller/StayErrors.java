package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses={CheckInController.class,CheckOutController.class})
public class StayErrors {
    @ExceptionHandler(StayException.class)
    public ResponseEntity<?> domain(StayException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception e) { return error(400,"INVALID_REQUEST","Dữ liệu gửi lên không hợp lệ."); }
    @ExceptionHandler({DataIntegrityViolationException.class,ConcurrencyFailureException.class})
    public ResponseEntity<?> conflict(Exception e) { return error(409,"CONCURRENT_CHANGE","Dữ liệu đã thay đổi hoặc thao tác xung đột. Hãy tải lại."); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message));
    }
}
