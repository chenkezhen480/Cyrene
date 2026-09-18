package com.harness.tool.shell;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a command may run, must be confirmed, or is refused.
 *
 * <p>Three outcomes, in order: a command matching the confirm table asks the operator; one matching
 * the allow table runs immediately; <b>anything else also asks the operator</b>. An allow list can
 * only anticipate the commands its author thought of, and the person reading the confirmation sees
 * the exact argv — a better judge of an unanticipated command than the list is. The cost is real
 * and worth stating: for unrecognised commands the confirmation prompt <em>is</em> the policy, so
 * if the operator approves without reading, nothing else is standing there.
 *
 * <p>A few commands are refused outright rather than offered for approval, because what they break
 * is this tool's own structure rather than a permission question: a second shell (which would
 * restore every metacharacter hazard argv-only invocation removes), a path instead of a bare
 * executable name (which would let a writable directory supply a binary sharing an allowed name),
 * and log following (which never returns). None of those become safe by being read carefully.
 *
 * <p>Rules match on the executable name plus a prefix of the argument list, compared token by
 * token. That granularity is the point: {@code docker ps} may be allowed while {@code docker rm} is
 * not, because the second token has to match exactly.
 *
 * <p>This class is <b>not</b> the only defence and should not be read as one. The real boundaries
 * are elsewhere: the command is never handed to a shell (see {@link ShellTool}), and per-tenant
 * tool permissions can remove {@code shell} from a run entirely.
 */
public final class CommandPolicy {

    public enum Decision { ALLOW, CONFIRM, DENY }

    /** A verdict plus the sentence explaining it, which is what the model actually learns from. */
    public record Verdict(Decision decision, String reason) {
        public boolean needsConfirmation() {
            return decision == Decision.CONFIRM;
        }

        public boolean denied() {
            return decision == Decision.DENY;
        }
    }

    /**
     * Executables that would hand control to a second shell. Invoking one would reinstate every
     * metacharacter hazard this tool's argv-only design exists to remove, so they are refused by
     * name rather than left to the rule tables.
     */
    private static final Set<String> SHELL_EXECUTABLES = Set.of(
            "bash", "sh", "zsh", "ksh", "fish", "csh", "tcsh", "dash", "ash", "busybox",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe",
            "wsl", "wsl.exe", "osascript", "cscript", "cscript.exe", "wscript", "wscript.exe");

    /** Read-only observation: inspecting state, not changing it. */
    private static final List<Rule> DEFAULT_ALLOW = List.of(
            rule("docker", "ps"), rule("docker", "logs"), rule("docker", "stats"),
            rule("docker", "inspect"), rule("docker", "images"), rule("docker", "version"),
            rule("docker", "info"), rule("docker", "top"), rule("docker", "port"),
            rule("docker", "compose", "ps"), rule("docker", "compose", "logs"),
            rule("docker", "compose", "config"), rule("docker", "compose", "top"),
            rule("git", "status"), rule("git", "diff"), rule("git", "log"),
            rule("git", "show"), rule("git", "branch"), rule("git", "remote"),
            rule("git", "rev-parse"), rule("git", "describe"), rule("git", "blame"),
            rule("java", "-version"), rule("java", "--version"),
            rule("mvn", "-v"), rule("mvn", "--version"),
            rule("node", "-v"), rule("node", "--version"),
            rule("npm", "-v"), rule("npm", "--version"),
            rule("gradle", "-v"), rule("gradle", "--version"),
            rule("python", "--version"), rule("python3", "--version"));

    /** Changes running state or executes project code. */
    private static final List<Rule> DEFAULT_CONFIRM = List.of(
            rule("docker", "restart"), rule("docker", "start"), rule("docker", "stop"),
            rule("docker", "compose", "up"), rule("docker", "compose", "down"),
            rule("docker", "compose", "restart"),
            rule("mvn", "test"), rule("mvn", "package"), rule("mvn", "compile"),
            rule("mvn", "verify"), rule("mvn", "install"),
            rule("gradle", "test"), rule("gradle", "build"),
            rule("npm", "test"), rule("npm", "install"), rule("npm", "run"),
            rule("git", "fetch"), rule("git", "pull"));

    private static final List<String> FOLLOW_FLAGS = List.of("-f", "--follow");
    private static final List<String> TAIL_FLAGS = List.of("--tail", "-n", "--since");

    private final List<Rule> allow;
    private final List<Rule> confirm;
    private final int logTailLines;

    CommandPolicy(List<Rule> allow, List<Rule> confirm, int logTailLines) {
        this.allow = List.copyOf(allow);
        this.confirm = List.copyOf(confirm);
        this.logTailLines = logTailLines;
    }

    /** Built-in tables plus whatever the operator appended. */
    public static CommandPolicy fromEnv() {
        EnvConfig config = EnvConfig.get();
        List<Rule> allow = new ArrayList<>(DEFAULT_ALLOW);
        allow.addAll(parseRules(config.getCommaList(EnvKey.SHELL_ALLOW)));
        List<Rule> confirm = new ArrayList<>(DEFAULT_CONFIRM);
        confirm.addAll(parseRules(config.getCommaList(EnvKey.SHELL_CONFIRM)));
        return new CommandPolicy(allow, confirm,
                config.getInt(EnvKey.SHELL_LOG_TAIL_LINES, 500));
    }

    public static CommandPolicy defaults() {
        return new CommandPolicy(DEFAULT_ALLOW, DEFAULT_CONFIRM, 500);
    }

    public Verdict decide(String command, List<String> args) {
        if (command == null || command.isBlank()) {
            return new Verdict(Decision.DENY, "command is required");
        }
        if (command.indexOf('/') >= 0 || command.indexOf('\\') >= 0) {
            // A path would let a writable directory supply a binary that merely shares a name with
            // an allowed one. Bare names resolve through PATH, which the Agent cannot write to.
            return new Verdict(Decision.DENY,
                    "command must be a bare executable name resolved from PATH, not a path: " + command);
        }
        String executable = command.toLowerCase(Locale.ROOT);
        if (SHELL_EXECUTABLES.contains(executable)) {
            return new Verdict(Decision.DENY,
                    executable + " is a shell; this tool never delegates to one. "
                            + "Pass the target command directly instead.");
        }
        // Only for log commands: -f is an ordinary filter flag everywhere else (docker ps -f).
        if (isLogCommand(command, args) && containsAny(args, FOLLOW_FLAGS)) {
            return new Verdict(Decision.DENY,
                    "following logs never returns; drop -f/--follow and use --tail instead");
        }
        // Confirmation is checked first so an operator who appends a broad rule to the confirm list
        // tightens the matching allow rules rather than losing to them.
        if (matches(confirm, executable, args)) {
            return new Verdict(Decision.CONFIRM, "changes running state or executes project code");
        }
        if (matches(allow, executable, args)) {
            return new Verdict(Decision.ALLOW, "read-only diagnostic command");
        }
        // Unrecognised commands are put to the operator rather than refused. An allow list can only
        // ever anticipate the commands its author thought of, and the operator is looking at the
        // exact argv — which is a better judge of an unanticipated command than the list is.
        return new Verdict(Decision.CONFIRM,
                "not on the allow list — approve only if you recognise it");
    }

    /**
     * Bounds {@code docker logs}, which is otherwise unbounded: a container that has been up for
     * weeks will happily stream megabytes into the model's context.
     *
     * @return the arguments to run with, tail-limited when the caller did not limit them
     */
    public List<String> applyDefaults(String command, List<String> args) {
        int subcommandEnd = subcommandEnd(command, args);
        if (subcommandEnd < 0 || containsAny(args, TAIL_FLAGS)) {
            return args;
        }
        List<String> bounded = new ArrayList<>(args);
        // Inserted right after the subcommand rather than appended, so it can never be mistaken for
        // a positional argument (a container name, say).
        bounded.addAll(subcommandEnd, List.of("--tail", String.valueOf(logTailLines)));
        return bounded;
    }

    /** {@code docker logs} ends at 1, {@code docker compose logs} at 2, anything else at -1. */
    private static int subcommandEnd(String command, List<String> args) {
        if (!"docker".equalsIgnoreCase(command)) {
            return -1;
        }
        if (!args.isEmpty() && "logs".equalsIgnoreCase(args.get(0))) {
            return 1;
        }
        if (args.size() >= 2 && "compose".equalsIgnoreCase(args.get(0))
                && "logs".equalsIgnoreCase(args.get(1))) {
            return 2;
        }
        return -1;
    }

    private static boolean isLogCommand(String command, List<String> args) {
        return subcommandEnd(command, args) >= 0;
    }

    private static boolean containsAny(List<String> args, List<String> needles) {
        for (String arg : args) {
            for (String needle : needles) {
                if (arg.equalsIgnoreCase(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(List<Rule> rules, String executable, List<String> args) {
        for (Rule candidate : rules) {
            if (candidate.matches(executable, args)) {
                return true;
            }
        }
        return false;
    }

    /** {@code "docker compose ps"} becomes executable {@code docker} with prefix {@code [compose, ps]}. */
    static Rule rule(String... tokens) {
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            rest.add(tokens[i]);
        }
        return new Rule(tokens[0], List.copyOf(rest));
    }

    private static List<Rule> parseRules(List<String> configured) {
        List<Rule> rules = new ArrayList<>();
        for (String entry : configured) {
            String[] tokens = entry.trim().split("\\s+");
            if (tokens.length >= 1 && !tokens[0].isEmpty()) {
                rules.add(rule(tokens));
            }
        }
        return rules;
    }

    private record Rule(String executable, List<String> argsPrefix) {

        boolean matches(String candidateExecutable, List<String> args) {
            if (!executable.equalsIgnoreCase(candidateExecutable)) {
                return false;
            }
            if (args.size() < argsPrefix.size()) {
                return false;
            }
            for (int i = 0; i < argsPrefix.size(); i++) {
                if (!matchesToken(argsPrefix.get(i), args.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Token equality, with one allowance: a rule token may match a {@code name=value} argument.
         *
         * <p>Without it a rule has to spell out values it cannot know — {@code --defaults-extra-file}
         * would never match {@code --defaults-extra-file=/etc/my.cnf}, and the operator would be left
         * wondering why their rule did nothing. The allowance is deliberately narrow: it requires the
         * rule token to be followed by {@code =}, so {@code status} still refuses {@code statusx}.
         */
        private static boolean matchesToken(String ruleToken, String argument) {
            return ruleToken.equalsIgnoreCase(argument)
                    || argument.regionMatches(true, 0, ruleToken, 0, ruleToken.length())
                    && argument.length() > ruleToken.length()
                    && argument.charAt(ruleToken.length()) == '=';
        }
    }
}
