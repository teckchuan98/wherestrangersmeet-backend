package com.wherestrangersmeet.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class BannedEmailException extends RuntimeException {
    public BannedEmailException(String message) {
        super(message);
    }
}
