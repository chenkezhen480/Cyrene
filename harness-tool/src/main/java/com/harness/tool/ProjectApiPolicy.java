package com.harness.tool;

import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;

import java.util.List;

/**
 * Central policy for deciding which discovered APIs may be exposed or called.
 */
final class ProjectApiPolicy {

    private ProjectApiPolicy() {
    }

    static boolean isCallable(ApiEndpoint endpoint) {
        return endpoint != null
                && endpoint.confirmed()
                && (!endpoint.isHighRisk() || endpoint.riskAcknowledged());
    }

    static String rejectionReason(ApiEndpoint endpoint) {
        if (endpoint == null) {
            return "Endpoint does not exist.";
        }
        if (!endpoint.confirmed()) {
            return "Endpoint '" + endpoint.id() + "' has not been confirmed by a human.";
        }
        if (endpoint.isHighRisk() && !endpoint.riskAcknowledged()) {
            return "Endpoint '" + endpoint.id()
                    + "' is a high-risk non-GET BOT endpoint and requires risk acknowledgement.";
        }
        return "";
    }

    static List<ApiEndpoint> callableEndpoints(ProjectApiConfig config) {
        if (config == null || config.endpoints() == null) {
            return List.of();
        }
        return config.endpoints().stream()
                .filter(ProjectApiPolicy::isCallable)
                .toList();
    }
}
