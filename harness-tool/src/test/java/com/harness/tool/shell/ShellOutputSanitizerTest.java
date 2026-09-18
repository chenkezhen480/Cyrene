package com.harness.tool.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellOutputSanitizerTest {

    @Test
    void redactsSensitiveContainerEnvironmentVariablesAndKeepsTheRest() {
        String inspect = """
                [{
                  "Name": "/mysql",
                  "Config": {
                    "Image": "mysql:8.0",
                    "Env": [
                      "MYSQL_ROOT_PASSWORD=sup3rs3cret",
                      "MYSQL_DATABASE=agent",
                      "MYSQL_USER=cyrene",
                      "MYSQL_PASSWORD=anothersecret",
                      "TZ=Asia/Shanghai"
                    ]
                  },
                  "NetworkSettings": {"Ports": {"3306/tcp": [{"HostPort": "3306"}]}}
                }]""";

        String sanitized = ShellOutputSanitizer.sanitize("docker", List.of("inspect", "mysql"), inspect);

        assertThat(sanitized).doesNotContain("sup3rs3cret").doesNotContain("anothersecret");
        // The parts that make inspect worth running must survive.
        assertThat(sanitized).contains("mysql:8.0").contains("MYSQL_DATABASE=agent")
                .contains("3306").contains("Asia/Shanghai");
    }

    @Test
    void redactsSensitiveTopLevelFieldsInInspectOutput() {
        String inspect = """
                [{"Name": "/redis", "Env": ["REDIS_PASSWORD=hunter2"], "Mounts": []}]""";

        String sanitized = ShellOutputSanitizer.sanitize("docker", List.of("inspect", "redis"), inspect);

        assertThat(sanitized).doesNotContain("hunter2");
        assertThat(sanitized).contains("Mounts");
    }

    @Test
    void masksKeyValuePairsInOrdinaryOutput() {
        String output = "DATABASE_PASSWORD=P@ssw0rd\nHOST=127.0.0.1\nAPI_KEY=abc123\n"
                + "SMTP_HOST: mail.example.com\nSECRET_TOKEN: zzz";

        String sanitized = ShellOutputSanitizer.sanitize("docker", List.of("ps"), output);

        assertThat(sanitized).doesNotContain("P@ssw0rd").doesNotContain("abc123")
                .doesNotContain("zzz");
        // Non-sensitive values are untouched, or the output stops being useful.
        assertThat(sanitized).contains("HOST=127.0.0.1").contains("SMTP_HOST: mail.example.com");
    }

    @Test
    void masksCredentialsEmbeddedInUrls() {
        String output = "connecting to mysql://root:sup3rsecret@127.0.0.1:3306/agent";

        String sanitized = ShellOutputSanitizer.sanitize("git", List.of("remote", "-v"), output);

        assertThat(sanitized).doesNotContain("sup3rsecret").contains("127.0.0.1:3306");
    }

    @Test
    void fallsBackToPatternsWhenInspectOutputIsNotJson() {
        String output = "Error response from daemon: MYSQL_ROOT_PASSWORD=leaked";

        String sanitized = ShellOutputSanitizer.sanitize("docker", List.of("inspect", "mysql"), output);

        assertThat(sanitized).doesNotContain("leaked");
        assertThat(sanitized).contains("Error response from daemon");
    }

    @Test
    void leavesInspectOutputOfOtherCommandsAlone() {
        String output = "{\"Config\":{\"Env\":[\"MYSQL_ROOT_PASSWORD=leaked\"]}}";

        // A different subcommand means we do not parse, but the pattern pass still applies.
        String sanitized = ShellOutputSanitizer.sanitize("docker", List.of("ps"), output);

        assertThat(sanitized).doesNotContain("leaked");
    }

    /**
     * The echoed command line is a second copy of the arguments, and arguments are where a
     * credential is most likely to sit. Whatever the tool prints back must go through here too.
     */
    @Test
    void masksCredentialsEchoedInTheCommandLine() {
        String echoed = "$ mysql -uroot --password=hunter2 -e select 1\nexit: 0\n";

        String sanitized = ShellOutputSanitizer.sanitize(
                "mysql", List.of("-uroot", "--password=hunter2"), echoed);

        assertThat(sanitized).doesNotContain("hunter2");
        assertThat(sanitized).contains("-uroot");
    }

    @Test
    void handlesEmptyAndNullOutput() {
        assertThat(ShellOutputSanitizer.sanitize("docker", List.of("ps"), null)).isEmpty();
        assertThat(ShellOutputSanitizer.sanitize("docker", List.of("ps"), "")).isEmpty();
    }
}
