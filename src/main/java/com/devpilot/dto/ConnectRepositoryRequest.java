package com.devpilot.dto;
import jakarta.validation.constraints.*;
public record ConnectRepositoryRequest(@NotNull @Positive Long githubRepositoryId) {}
