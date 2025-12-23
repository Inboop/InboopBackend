package com.inboop.backend.workspace.controller;

import com.inboop.backend.workspace.dto.WorkspaceErrorResponse;
import com.inboop.backend.workspace.exception.WorkspaceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for workspace-related exceptions.
 */
@RestControllerAdvice(basePackages = "com.inboop.backend.workspace")
public class WorkspaceExceptionHandler {

    @ExceptionHandler(WorkspaceException.class)
    public ResponseEntity<WorkspaceErrorResponse> handleWorkspaceException(WorkspaceException ex) {
        WorkspaceErrorResponse response = new WorkspaceErrorResponse(ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(response);
    }
}
