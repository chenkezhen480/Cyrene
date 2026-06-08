package com.harness.cli;

import com.harness.agent.AgentOrchestrator;
import com.harness.core.model.AgentResult;
import com.harness.core.model.ReActStep;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;

import java.util.Arrays;
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

        if (!EnvConfig.get().getBool(EnvKey.CLI_ENABLED, true)) {
            System.out.println("CLI disabled (" + EnvKey.CLI_ENABLED + "=false), exiting");
            return;
        }

        boolean useStream = Arrays.asList(args).contains("--stream");

        System.out.println("=== Harness Agent v0.1.0 ===");
        System.out.println("LLM: " + EnvConfig.get().getString("HARNESS_LLM_PROVIDER", "openai"));
        System.out.println("Model: " + EnvConfig.get().getString("HARNESS_LLM_MODEL", "default"));
        System.out.println("Mode: " + (useStream ? "streaming" : "blocking"));
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
                if (useStream) {
                    System.out.print("\nAgent> ");
                    agent.streamRun(null, input, Collections.emptyList(), null, null, null,
                            event -> {
                                switch (event.type()) {
                                    case TOKEN -> System.out.print(event.data());
                                    case STEP -> {
                                        ReActStep step = (ReActStep) event.metadata().get("step");
                                        System.out.printf("%n[Step %d: %s]%n", step.stepNumber(), step.action());
                                    }
                                    case DONE -> System.out.printf(
                                            "%n[Trace: %s, Steps: %s]%n",
                                            event.metadata().get("traceId"),
                                            event.metadata().get("steps"));
                                    case ERROR -> System.out.println("\nError> " + event.data());
                                }
                            });
                    System.out.println();
                } else {
                    AgentResult result = agent.run(null, input, Collections.emptyList());
                    System.out.println("\nAgent> " + result.output());
                    System.out.println("[Trace: " + result.trace().traceId() +
                            ", Steps: " + result.steps().size() +
                            ", Risk: " + result.riskLevel() + "]\n");
                }
            } catch (Exception e) {
                System.out.println("\nError> " + e.getMessage() + "\n");
            }
        }
    }
}
