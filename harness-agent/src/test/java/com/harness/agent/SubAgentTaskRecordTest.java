package com.harness.agent;

import com.harness.core.model.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentTaskRecordTest {

    @Test
    void incompleteIsTerminalAndCannotBeOverwrittenAsSuccess() {
        SubAgentTask task = new SubAgentTask(
                "task-1", "description", "context", "persona", "prompt",
                List.of(), List.of(),
                new SubAgentCompletionContract(null, null, null));
        SubAgentTaskRecord record = new SubAgentTaskRecord(
                task.taskId(), "run-1", "session-1", task,
                new CancellationToken());
        record.start();
        ContractValidation validation = new ContractValidation(
                true, false, ContractValidation.Status.FAILED_CONTRACT,
                List.of("unmet"));
        SubAgentCompletionContractValidator.Evaluation evaluation =
                new SubAgentCompletionContractValidator.Evaluation(
                        List.of(), ToolExecutionSummary.empty(), validation, null);
        SubAgentResult incomplete = SubAgentResult.incomplete(
                task.taskId(), "partial", evaluation, 1, "trace-1");

        record.markIncomplete(incomplete);
        record.succeed(SubAgentResult.success(
                task.taskId(), "late success",
                new SubAgentCompletionContractValidator.Evaluation(
                        List.of(), ToolExecutionSummary.empty(),
                        ContractValidation.notDeclared(), null),
                2, "trace-2"));

        assertThat(record.status().get()).isEqualTo(SubAgentStatus.INCOMPLETE);
        assertThat(record.isTerminal()).isTrue();
        assertThat(record.completion().join()).isSameAs(incomplete);
    }
}
