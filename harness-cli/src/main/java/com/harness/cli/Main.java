package com.harness.cli;

import com.harness.agent.AgentOrchestrator;
import com.harness.core.model.AgentResult;
import com.harness.env.EnvConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Scanner;

/**
 * CLI entry point for Harness Agent.
 * Run with: java -jar harness-cli.jar
 *
 * All configuration is read from environment variables (HARNESS_*).
 */
public class Main {

    public static void main(String[] args) {
        // Load env config (supports .env file override via args)
        EnvConfig.init(Collections.emptyMap());

        if (!EnvConfig.get().getBool("HARNESS_CLI_ENABLED", true)) {
            System.out.println("CLI disabled (HARNESS_CLI_ENABLED=false), exiting");
            return;
        }

        System.out.println("=== Harness Agent v0.1.0 ===");
        System.out.println("LLM: " + EnvConfig.get().getString("HARNESS_LLM_PROVIDER", "openai"));
        System.out.println("Model: " + EnvConfig.get().getString("HARNESS_LLM_MODEL", "default"));
        System.out.println("Type 'quit' to exit.\n");

        AgentOrchestrator agent = new AgentOrchestrator();

        Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdown));

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("You> ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye.");
                break;
            }

            if (input.isEmpty()) continue;

            try {
                AgentResult result = agent.run(null, input, Collections.emptyList());
                System.out.println("\nAgent> " + result.output());
                System.out.println("[Trace: " + result.trace().traceId() +
                        ", Steps: " + result.steps().size() +
                        ", Risk: " + result.riskLevel() + "]\n");
            } catch (Exception e) {
                System.out.println("\nError> " + e.getMessage() + "\n");
            }
        }
    }
}
