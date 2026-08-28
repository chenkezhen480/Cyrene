package com.harness.server;

import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;

import java.io.IOException;
import java.util.Objects;

/** HTTP boundary for model configuration management. */
public final class ModelConfigurationHandler {

    private final ModelConfigurationService configurationService;

    public ModelConfigurationHandler(ModelConfigurationService configurationService) {
        this.configurationService = Objects.requireNonNull(
                configurationService,
                "configurationService");
    }

    public void get(Context context) {
        try {
            context.json(configurationService.current());
        } catch (IOException e) {
            ApiResponses.error(
                    context,
                    500,
                    ApiErrorCode.INTERNAL_ERROR,
                    "Failed to read model configuration: " + e.getMessage());
        }
    }

    public void update(Context context) {
        try {
            ModelConfigurationService.ModelConfigurationUpdateRequest request =
                    context.bodyAsClass(
                            ModelConfigurationService.ModelConfigurationUpdateRequest.class);
            context.json(configurationService.update(request));
        } catch (IllegalArgumentException e) {
            ApiResponses.error(
                    context,
                    400,
                    ApiErrorCode.INVALID_REQUEST,
                    e.getMessage());
        } catch (IOException e) {
            ApiResponses.error(
                    context,
                    500,
                    ApiErrorCode.INTERNAL_ERROR,
                    "Failed to save model configuration: " + e.getMessage());
        }
    }
}
