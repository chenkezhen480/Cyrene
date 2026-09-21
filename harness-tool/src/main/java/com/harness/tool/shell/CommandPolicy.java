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
 * the exact command line — a better judge of an unanticipated command than the list is.
 *
 * <p>A few commands and syntax patterns are refused outright rather than offered for approval,
 * because they violate safety invariants: file redirection ({@code >}, {@code >>}, {@code <}),
 * file-writing utilities ({@code tee}, {@code out-file}, etc.), subshell spawning ({@code bash},
 * {@code sh}, etc.), paths instead of bare executable names (which would let a writable directory
 * supply a binary sharing an allowed name), and log following (which never returns). None of those
 * become safe by being read carefully.
 *
 * <p>Rules match on the executable name plus a prefix of the argument list, token by token.
 * In a pipeline (e.g. {@code ps aux | grep java}), each stage is verified individually.
 *
 * <p>This class works together with {@link ShellTool}, which executes commands in the system shell
 * with strict byte output ceilings, timeout limits, and per-tenant tool permissions.
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
     * Executables that would hand control to an interactive or secondary shell.
     */
    private static final Set<String> SHELL_EXECUTABLES = Set.of(
            "bash", "sh", "zsh", "ksh", "fish", "csh", "tcsh", "dash", "ash", "busybox",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe",
            "wsl", "wsl.exe", "osascript", "cscript", "cscript.exe", "wscript", "wscript.exe");

    /** File-writing utilities prohibited in read-only diagnostic shell execution. */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "tee", "out-file", "set-content", "add-content");

    /** Read-only observation: inspecting state, not changing it. */
    private static final List<Rule> DEFAULT_ALLOW = List.of(
            // Docker
            rule("docker", "ps"), rule("docker", "logs"), rule("docker", "stats"),
            rule("docker", "inspect"), rule("docker", "images"), rule("docker", "version"),
            rule("docker", "info"), rule("docker", "top"), rule("docker", "port"),
            rule("docker", "compose", "ps"), rule("docker", "compose", "logs"),
            rule("docker", "compose", "config"), rule("docker", "compose", "top"),
            // Git
            rule("git", "status"), rule("git", "diff"), rule("git", "log"),
            rule("git", "show"), rule("git", "branch"), rule("git", "remote"),
            rule("git", "rev-parse"), rule("git", "describe"), rule("git", "blame"),
            // Runtime versions
            rule("java", "-version"), rule("java", "--version"),
            rule("mvn", "-v"), rule("mvn", "--version"),
            rule("node", "-v"), rule("node", "--version"),
            rule("npm", "-v"), rule("npm", "--version"),
            rule("gradle", "-v"), rule("gradle", "--version"),
            rule("python", "--version"), rule("python3", "--version"),
            // System and network diagnostics
            rule("netstat"), rule("ss"), rule("ps"), rule("tasklist"), rule("lsof"),
            // Output filtering and text processing
            rule("grep"), rule("findstr"), rule("head"), rule("tail"), rule("wc"),
            rule("cat"), rule("type"), rule("more"), rule("sort"), rule("uniq"),
            // Basic system information
            rule("df"), rule("free"), rule("uptime"), rule("whoami"),
            rule("hostname"), rule("uname"), rule("echo"), rule("ls"), rule("dir"),
            rule("ping"), rule("tracert"), rule("traceroute"));

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
        String fullLine = assembleCommandLine(command, args);
        if (hasFileRedirection(fullLine)) {
            return new Verdict(Decision.DENY,
                    "file redirection ('>', '>>', '<') is strictly prohibited; shell cannot write to or redirect files");
        }

        List<String> parts = splitCommandStages(fullLine);
        if (parts.isEmpty()) {
            return new Verdict(Decision.DENY, "command is required");
        }

        Verdict pendingConfirm = null;
        for (int i = 0; i < parts.size(); i += 2) {
            String stage = parts.get(i);
            List<String> tokens = tokenize(stage);
            if (tokens.isEmpty()) {
                continue;
            }
            String rawExecutable = tokens.get(0);
            List<String> stageArgs = tokens.subList(1, tokens.size());

            Verdict stageVerdict = decideStage(rawExecutable, stageArgs);
            if (stageVerdict.denied()) {
                return stageVerdict;
            }
            if (stageVerdict.needsConfirmation() && pendingConfirm == null) {
                pendingConfirm = stageVerdict;
            }
        }

        if (pendingConfirm != null) {
            return pendingConfirm;
        }
        return new Verdict(Decision.ALLOW, "read-only diagnostic command");
    }

    private Verdict decideStage(String rawExecutable, List<String> stageArgs) {
        String executable = stripQuotes(rawExecutable);
        if (executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
            return new Verdict(Decision.DENY,
                    "command must be a bare executable name resolved from PATH, not a path: " + executable);
        }

        String name = executable.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }

        if (SHELL_EXECUTABLES.contains(name)) {
            return new Verdict(Decision.DENY,
                    executable + " is a shell; this tool never delegates to one. Pass the target command directly instead.");
        }

        if (WRITE_COMMANDS.contains(name)) {
            return new Verdict(Decision.DENY,
                    "file writing via '" + executable + "' is strictly prohibited in shell");
        }

        List<String> strippedArgs = stageArgs.stream().map(CommandPolicy::stripQuotes).toList();

        if (isLogCommand(executable, strippedArgs) && containsAny(strippedArgs, FOLLOW_FLAGS)) {
            return new Verdict(Decision.DENY,
                    "following logs never returns; drop -f/--follow and use --tail instead");
        }

        if (matches(confirm, name, strippedArgs)) {
            return new Verdict(Decision.CONFIRM, "changes running state or executes project code");
        }

        if (matches(allow, name, strippedArgs)) {
            return new Verdict(Decision.ALLOW, "read-only diagnostic command");
        }

        return new Verdict(Decision.CONFIRM, "not on the allow list — approve only if you recognise it");
    }

    /**
     * Bounds {@code docker logs} and {@code git log}, which are otherwise unbounded: a container
     * that has been up for weeks or a repository with thousands of commits will stream
     * megabytes into the model's context.
     */
    public List<String> applyDefaults(String command, List<String> args) {
        if ("git".equalsIgnoreCase(command) && !args.isEmpty() && "log".equalsIgnoreCase(args.get(0))) {
            if (!hasGitLogLimit(args)) {
                List<String> bounded = new ArrayList<>(args);
                bounded.addAll(1, List.of("-n", "50"));
                return bounded;
            }
            return args;
        }

        int subcommandEnd = subcommandEnd(command, args);
        if (subcommandEnd < 0 || containsAny(args, TAIL_FLAGS)) {
            return args;
        }
        List<String> bounded = new ArrayList<>(args);
        bounded.addAll(subcommandEnd, List.of("--tail", String.valueOf(logTailLines)));
        return bounded;
    }

    /**
     * Applies default bounds to each command stage in a pipeline.
     */
    public String applyDefaults(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return commandLine;
        }
        List<String> parts = splitCommandStages(commandLine);
        if (parts.isEmpty()) {
            return commandLine;
        }
        StringBuilder bounded = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (i % 2 != 0) {
                bounded.append(" ").append(part).append(" ");
            } else {
                List<String> tokens = tokenize(part);
                if (tokens.isEmpty()) {
                    bounded.append(part);
                } else {
                    String executable = stripQuotes(tokens.get(0));
                    List<String> stageArgs = tokens.subList(1, tokens.size());
                    List<String> effectiveArgs = applyDefaults(executable, stageArgs);
                    if (effectiveArgs.equals(stageArgs)) {
                        bounded.append(part);
                    } else {
                        bounded.append(assembleCommandLine(tokens.get(0), effectiveArgs));
                    }
                }
            }
        }
        return bounded.toString().trim();
    }

    public static boolean hasFileRedirection(String line) {
        if (line == null) {
            return false;
        }
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escapeNext = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escapeNext) {
                escapeNext = false;
                continue;
            }
            if (c == '\\') {
                escapeNext = true;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (!inDoubleQuote && !inSingleQuote) {
                if (c == '<') {
                    return true;
                }
                if (c == '>') {
                    if (isDescriptorRedirection(line, i)) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDescriptorRedirection(String line, int gtIndex) {
        if (gtIndex + 2 < line.length()
                && line.charAt(gtIndex + 1) == '&'
                && (line.charAt(gtIndex + 2) == '1' || line.charAt(gtIndex + 2) == '2')) {
            return true;
        }
        return false;
    }

    public static List<String> splitCommandStages(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return List.of();
        }
        List<String> stagesAndSeps = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escapeNext = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (escapeNext) {
                current.append(c);
                escapeNext = false;
                continue;
            }
            if (c == '\\') {
                escapeNext = true;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
            } else if (!inDoubleQuote && !inSingleQuote) {
                boolean isOperator = false;
                if (c == '|' || c == ';') {
                    isOperator = true;
                } else if (c == '&') {
                    if (i > 0 && (commandLine.charAt(i - 1) == '>' || commandLine.charAt(i - 1) == '<')) {
                        isOperator = false;
                    } else {
                        isOperator = true;
                    }
                }

                if (isOperator) {
                    char next = (i + 1 < commandLine.length()) ? commandLine.charAt(i + 1) : '\0';
                    String op;
                    if ((c == '|' && next == '|') || (c == '&' && next == '&')) {
                        op = "" + c + next;
                        i++;
                    } else {
                        op = "" + c;
                    }
                    stagesAndSeps.add(current.toString().trim());
                    stagesAndSeps.add(op);
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            } else {
                current.append(c);
            }
        }
        stagesAndSeps.add(current.toString().trim());
        return stagesAndSeps;
    }

    public static List<String> tokenize(String stage) {
        if (stage == null || stage.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escapeNext = false;
        for (int i = 0; i < stage.length(); i++) {
            char c = stage.charAt(i);
            if (escapeNext) {
                current.append(c);
                escapeNext = false;
            } else if (c == '\\') {
                escapeNext = true;
                current.append(c);
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
            } else if (Character.isWhitespace(c) && !inDoubleQuote && !inSingleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    public static String stripQuotes(String s) {
        if (s == null || s.length() < 2) {
            return s;
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static String assembleCommandLine(String command, List<String> args) {
        if (command == null || command.isBlank()) {
            return "";
        }
        if (args == null || args.isEmpty()) {
            return command.trim();
        }
        StringBuilder sb = new StringBuilder(command.trim());
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            sb.append(' ');
            if (arg.equals("|") || arg.equals("2>&1") || arg.equals("1>&2")) {
                sb.append(arg);
            } else if ((arg.startsWith("\"") && arg.endsWith("\"")) || (arg.startsWith("'") && arg.endsWith("'"))) {
                sb.append(arg);
            } else if (arg.contains(" ") || arg.contains("\t")) {
                sb.append('"').append(arg.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private static boolean hasGitLogLimit(List<String> args) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase("-n") || arg.startsWith("--max-count")
                    || arg.matches("^-\\d+$")) {
                return true;
            }
        }
        return false;
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
