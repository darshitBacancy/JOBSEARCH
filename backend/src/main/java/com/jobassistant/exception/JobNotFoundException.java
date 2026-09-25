package com.jobassistant.exception;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(Long id) {
        super("Job " + id + " was not found");
    }
}
