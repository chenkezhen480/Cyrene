package com.harness.agent;

import java.util.List;

/** Outcome of checking an optional sub-agent completion contract. */
public record ContractValidation(
        boolean declared,
        boolean satisfied,
        Status status,
        List<String> violations
) {
    public enum Status {
        NOT_DECLARED,
        SATISFIED,
        FAILED_CONTRACT,
        NOT_EVALUATED
    }

    public ContractValidation {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static ContractValidation notDeclared() {
        return new ContractValidation(false, true, Status.NOT_DECLARED, List.of());
    }

    public static ContractValidation notEvaluated(boolean declared) {
        return declared
                ? new ContractValidation(true, false, Status.NOT_EVALUATED, List.of())
                : notDeclared();
    }
}
