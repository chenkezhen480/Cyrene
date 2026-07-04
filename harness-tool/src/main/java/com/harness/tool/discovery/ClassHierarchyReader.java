package com.harness.tool.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic class hierarchy reader.
 * Given a class name, finds the source file, reads fields + annotations,
 * then recursively reads parent classes up to 2 levels.
 * Returns the merged class structure (child fields override parent).
 *
 * <p>Supports: Java (extends/implements), Python (class Foo(Bar)),
 * JS/TS (class Foo extends Bar).
 */
public class ClassHierarchyReader {

    private static final Logger log = LoggerFactory.getLogger(ClassHierarchyReader.class);

    /** Max recursion depth (parent levels). */
    private static final int MAX_DEPTH = 2;

    /** Directories to skip during file search. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".", "..", ".git", ".svn", "node_modules", "target", "build",
            "dist", "out", ".idea", ".vscode", "vendor", "venv", ".venv", "__pycache__"
    );

    /** Java type → JSON Schema type mapping. */
    private static final Map<String, String> JAVA_TYPE_MAP = Map.ofEntries(
            Map.entry("String", "string"),
            Map.entry("string", "string"),
            Map.entry("Integer", "integer"), Map.entry("int", "integer"),
            Map.entry("Long", "integer"), Map.entry("long", "integer"),
            Map.entry("Short", "integer"), Map.entry("short", "integer"),
            Map.entry("Byte", "integer"), Map.entry("byte", "integer"),
            Map.entry("Double", "number"), Map.entry("double", "number"),
            Map.entry("Float", "number"), Map.entry("float", "number"),
            Map.entry("BigDecimal", "number"),
            Map.entry("Boolean", "boolean"), Map.entry("boolean", "boolean"),
            Map.entry("Date", "string"), Map.entry("LocalDateTime", "string"),
            Map.entry("LocalDate", "string"), Map.entry("LocalTime", "string"),
            Map.entry("Instant", "string"), Map.entry("Timestamp", "string")
    );

    /** C# type → JSON Schema type mapping. */
    private static final Map<String, String> CSHARP_TYPE_MAP = Map.ofEntries(
            Map.entry("string", "string"), Map.entry("String", "string"),
            Map.entry("int", "integer"), Map.entry("Int32", "integer"),
            Map.entry("long", "integer"), Map.entry("Int64", "integer"),
            Map.entry("short", "integer"), Map.entry("Int16", "integer"),
            Map.entry("byte", "integer"), Map.entry("sbyte", "integer"),
            Map.entry("double", "number"), Map.entry("Double", "number"),
            Map.entry("float", "number"), Map.entry("Single", "number"),
            Map.entry("decimal", "number"), Map.entry("Decimal", "number"),
            Map.entry("bool", "boolean"), Map.entry("Boolean", "boolean"),
            Map.entry("DateTime", "string"), Map.entry("DateTimeOffset", "string"),
            Map.entry("DateOnly", "string"), Map.entry("TimeOnly", "string"),
            Map.entry("Guid", "string"), Map.entry("Uri", "string"),
            Map.entry("object", "object"), Map.entry("dynamic", "object")
    );

    /** C/C++ type → JSON Schema type mapping. */
    private static final Map<String, String> C_TYPE_MAP = Map.ofEntries(
            Map.entry("char", "string"), Map.entry("wchar_t", "string"),
            Map.entry("string", "string"), Map.entry("std::string", "string"),
            Map.entry("std::wstring", "string"),
            Map.entry("int", "integer"), Map.entry("int32_t", "integer"),
            Map.entry("int64_t", "integer"), Map.entry("int16_t", "integer"),
            Map.entry("int8_t", "integer"),
            Map.entry("long", "integer"), Map.entry("long long", "integer"),
            Map.entry("short", "integer"), Map.entry("unsigned", "integer"),
            Map.entry("uint32_t", "integer"), Map.entry("uint64_t", "integer"),
            Map.entry("size_t", "integer"), Map.entry("ptrdiff_t", "integer"),
            Map.entry("double", "number"), Map.entry("float", "number"),
            Map.entry("long double", "number"),
            Map.entry("bool", "boolean"), Map.entry("_Bool", "boolean")
    );

    // ─── Java patterns ───
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile(
            "\\bclass\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+[\\w,\\s<>]+)?\\s*\\{");
    private static final Pattern JAVA_FIELD_PATTERN = Pattern.compile(
            "(?:(?:private|protected|public)\\s+)?(?:static\\s+)?(?:final\\s+)?" +
            "(\\w+(?:<[^>]+>)?(?:\\[\\])?)\\s+(\\w+)\\s*(?:=|;)");
    private static final Pattern JAVA_ANNOTATION_PATTERN = Pattern.compile(
            "@(NotNull|NotBlank|NotEmpty|Size|Length|Min|Max|Pattern|Email|Valid|DecimalMin|DecimalMax|Positive|Negative|Past|Future|Range)\\s*(?:\\(([^)]*)\\))?");

    // ─── Python patterns ───
    private static final Pattern PY_CLASS_PATTERN = Pattern.compile(
            "class\\s+(\\w+)(?:\\(([^)]+)\\))?\\s*:");
    private static final Pattern PY_FIELD_PATTERN = Pattern.compile(
            "(\\w+)\\s*:\\s*(\\w+)");
    private static final Pattern PY_ANNOTATION_PATTERN = Pattern.compile(
            "@(NotNull|NotBlank|NotEmpty|Email|Min|Max|Pattern)\\s*(?:\\(([^)]*)\\))?");

    // ─── JS/TS patterns ───
    private static final Pattern TS_CLASS_PATTERN = Pattern.compile(
            "class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?\\s*\\{");
    private static final Pattern TS_FIELD_PATTERN = Pattern.compile(
            "(\\w+)\\s*:\\s*(\\w+)");

    // ─── C# patterns ───
    private static final Pattern CSHARP_CLASS_PATTERN = Pattern.compile(
            "\\bclass\\s+(\\w+)(?:\\s*:\\s*(\\w+)(?:\\s*,\\s*[\\w,\\s]+)?)?\\s*\\{?");
    private static final Pattern CSHARP_PROP_PATTERN = Pattern.compile(
            "(?:public|private|protected|internal)\\s+(?:static\\s+)?(?:readonly\\s+)?" +
            "(\\w+(?:<[^>]+>)?(?:\\[\\])?(?:\\?)?)\\s+(\\w+)\\s*\\{");
    private static final Pattern CSHARP_FIELD_PATTERN = Pattern.compile(
            "(?:public|private|protected|internal)\\s+(?:static\\s+)?(?:readonly\\s+)?" +
            "(\\w+(?:<[^>]+>)?(?:\\[\\])?(?:\\?)?)\\s+(_?\\w+)\\s*(?:=|;)");
    private static final Pattern CSHARP_ANNOTATION_PATTERN = Pattern.compile(
            "\\[(Required|StringLength|Range|MinLength|MaxLength|RegularExpression|EmailAddress|Phone)\\s*(?:\\(([^)]*)\\))?\\]");

    // ─── C++ patterns ───
    private static final Pattern CPP_CLASS_PATTERN = Pattern.compile(
            "\\bclass\\s+(\\w+)(?:\\s*:\\s*(?:(?:public|private|protected)\\s+)(\\w+)(?:\\s*,\\s*(?:(?:public|private|protected)\\s+)[\\w,\\s]+)?)?\\s*\\{?");
    private static final Pattern CPP_STRUCT_PATTERN = Pattern.compile(
            "\\bstruct\\s+(\\w+)(?:\\s*:\\s*(?:(?:public|private|protected)\\s+)(\\w+)(?:\\s*,\\s*(?:(?:public|private|protected)\\s+)[\\w,\\s]+)?)?\\s*\\{?");
    private static final Pattern CPP_FIELD_PATTERN = Pattern.compile(
            "(?:(?:public|private|protected)\\s*:\\s*)?" +
            "((?:std::)?\\w+(?:<[^>]+>)?(?:\\s*\\*)?(?:\\s*&)?)\\s+(\\w+)\\s*(?:=|;|\\{)");

    // ─── C patterns ───
    private static final Pattern C_STRUCT_PATTERN = Pattern.compile(
            "\\bstruct\\s+(\\w+)\\s*\\{");
    private static final Pattern C_FIELD_PATTERN = Pattern.compile(
            "((?:const\\s+)?(?:unsigned\\s+)?(?:signed\\s+)?\\w+(?:\\s*\\*)?)\\s+(\\w+)\\s*(?:;|\\[)");

    // ─── PHP patterns ───
    private static final Pattern PHP_CLASS_PATTERN = Pattern.compile(
            "\\bclass\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+[\\w,\\s\\\\]+)?\\s*\\{");
    private static final Pattern PHP_FIELD_PATTERN = Pattern.compile(
            "(?:public|protected|private)\\s+(?:(?:static|readonly)\\s+)?(?:\\??(\\w+(?:<[^>]+>)?(?:\\[\\])?))?\\s+\\$(\\w+)");
    private static final Pattern PHP_ANNOTATION_PATTERN = Pattern.compile(
            "#\\[(NotNull|NotBlank|NotEmpty|Email|Type|Range|Count|Choice|Regex|Assert|Valid)\\s*(?:\\(([^)]*)\\))?\\]");

    // ─── Rust patterns ───
    private static final Pattern RUST_STRUCT_PATTERN = Pattern.compile(
            "\\bstruct\\s+(\\w+)(?:\\s*<[^>]*>)?\\s*\\{");
    private static final Pattern RUST_FIELD_PATTERN = Pattern.compile(
            "(?:pub(?:\\([^)]+\\))?\\s+)?(\\w+)\\s*:\\s*(\\w+(?:<[^>]+>)?(?:\\[\\w+;\\s*\\d+\\])?)");
    private static final Pattern RUST_TUPLE_STRUCT_PATTERN = Pattern.compile(
            "\\bstruct\\s+(\\w+)(?:\\s*<[^>]*>)?\\s*\\(([^)]+)\\)");

    /**
     * Represents a single class's parsed structure.
     */
    public static class ClassInfo {
        public final String name;
        public final String filePath;
        public final String parentClass;  // null if no parent
        public final List<FieldInfo> fields;
        public final int depth;           // 0 = target class, 1 = parent, 2 = grandparent

        public ClassInfo(String name, String filePath, String parentClass, List<FieldInfo> fields, int depth) {
            this.name = name;
            this.filePath = filePath;
            this.parentClass = parentClass;
            this.fields = fields;
            this.depth = depth;
        }
    }

    /**
     * Represents a single field in a class.
     */
    public static class FieldInfo {
        public final String name;
        public final String type;
        public final String jsonType;       // JSON Schema type
        public final List<String> annotations;  // validation annotations
        public final String sourceClass;    // which class this field came from

        public FieldInfo(String name, String type, String jsonType, List<String> annotations, String sourceClass) {
            this.name = name;
            this.type = type;
            this.jsonType = jsonType;
            this.annotations = annotations;
            this.sourceClass = sourceClass;
        }
    }

    private final Path sourceRoot;
    private final Path projectRoot;  // actual project root (has pom.xml/build.gradle), used for cross-module search

    public ClassHierarchyReader(Path sourceRoot) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.projectRoot = detectProjectRoot(this.sourceRoot);
        if (!this.projectRoot.equals(this.sourceRoot)) {
            log.debug("[ClassHierarchy] Expanded search root: {} → {}", this.sourceRoot, this.projectRoot);
        }
    }

    /**
     * Walk up from sourceRoot to find the repository root (.git directory).
     * Falls back to sourceRoot if no .git found.
     */
    private static Path detectProjectRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return start;
    }

    /**
     * Read a class and its parent hierarchy (up to 2 levels).
     *
     * @param className simple class name (e.g. "UserDTO", "CourseVO")
     * @return list of ClassInfo from child → parent → grandparent (depth 0, 1, 2)
     */
    public List<ClassInfo> readHierarchy(String className) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException("项目目录不存在: " + sourceRoot);
        }
        List<ClassInfo> hierarchy = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        readRecursive(className, 0, hierarchy, visited);
        return hierarchy;
    }

    /**
     * Read hierarchy and merge into a single field list.
     * Child fields override parent fields with the same name.
     *
     * @return merged fields (child takes priority), ordered by declaration
     */
    public List<FieldInfo> readMergedFields(String className) {
        List<ClassInfo> hierarchy = readHierarchy(className);
        Map<String, FieldInfo> merged = new LinkedHashMap<>();

        // Process from grandparent → parent → child (so child overrides)
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            for (FieldInfo field : hierarchy.get(i).fields) {
                merged.put(field.name, field);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void readRecursive(String className, int depth, List<ClassInfo> result, Set<String> visited) {
        if (depth > MAX_DEPTH) return;
        if (className == null || className.isEmpty()) return;
        if (visited.contains(className)) return;  // avoid circular inheritance
        visited.add(className);

        // Find the source file (returns absolute path)
        Optional<Path> filePath = findClassFile(className);
        if (filePath.isEmpty()) {
            log.debug("[ClassHierarchy] Class file not found: {} (depth={})", className, depth);
            return;
        }

        try {
            Path absPath = filePath.get();
            String content = Files.readString(absPath);
            ClassInfo info = parseClass(className, absPath.toString(), content, depth);

            if (info != null) {
                result.add(info);
                // Recurse into parent
                if (info.parentClass != null) {
                    readRecursive(info.parentClass, depth + 1, result, visited);
                }
            }
        } catch (IOException e) {
            log.debug("[ClassHierarchy] Failed to read {}: {}", filePath.get(), e.getMessage());
        }
    }

    /**
     * Find a class file by simple name using glob patterns.
     * Searches sourceRoot first, then falls back to projectRoot for cross-module classes.
     */
    private Optional<Path> findClassFile(String className) {
        List<String> patterns = List.of(
                "**/" + className + ".java",
                "**/" + className + ".py",
                "**/" + className + ".ts",
                "**/" + className + ".js",
                "**/" + className + ".cs",
                "**/" + className + ".cpp",
                "**/" + className + ".hpp",
                "**/" + className + ".cc",
                "**/" + className + ".cxx",
                "**/" + className + ".c",
                "**/" + className + ".h",
                "**/" + className + ".go",
                "**/" + className + ".php",
                "**/" + className + ".rs"
        );

        // Search in sourceRoot first, then projectRoot for cross-module classes
        List<Path> searchRoots = new ArrayList<>();
        searchRoots.add(sourceRoot);
        if (!projectRoot.equals(sourceRoot)) {
            searchRoots.add(projectRoot);
        }

        for (Path root : searchRoots) {
            for (String pattern : patterns) {
                Optional<Path> result = globSearch(root, pattern);
                if (result.isPresent()) {
                    if (!root.equals(sourceRoot)) {
                        log.debug("[ClassHierarchy] Found {} in project root (cross-module): {}", className, result.get());
                    }
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Search for a file using a single glob pattern under the given root.
     * Returns absolute path if found.
     */
    private Optional<Path> globSearch(Path root, String pattern) {
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> found = new ArrayList<>();

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    return SKIP_DIRS.contains(dirName) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = root.relativize(file);
                    if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                        found.add(file.toAbsolutePath());
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (!found.isEmpty()) {
                return Optional.of(found.get(0));
            }
        } catch (IOException e) {
            // ignore
        }
        return Optional.empty();
    }

    /**
     * Parse a class file and extract fields, annotations, and parent class.
     */
    private ClassInfo parseClass(String className, String filePath, String content, int depth) {
        String lower = filePath.toLowerCase();

        if (lower.endsWith(".java")) {
            return parseJavaClass(className, filePath, content, depth);
        } else if (lower.endsWith(".py")) {
            return parsePythonClass(className, filePath, content, depth);
        } else if (lower.endsWith(".ts") || lower.endsWith(".js")) {
            return parseTsClass(className, filePath, content, depth);
        } else if (lower.endsWith(".cs")) {
            return parseCSharpClass(className, filePath, content, depth);
        } else if (lower.endsWith(".cpp") || lower.endsWith(".hpp") || lower.endsWith(".cc")
                || lower.endsWith(".cxx") || lower.endsWith(".h")) {
            return parseCppClass(className, filePath, content, depth);
        } else if (lower.endsWith(".c")) {
            return parseCStruct(className, filePath, content, depth);
        } else if (lower.endsWith(".php")) {
            return parsePhpClass(className, filePath, content, depth);
        } else if (lower.endsWith(".rs")) {
            return parseRustStruct(className, filePath, content, depth);
        }

        // Fallback: try Java parser
        return parseJavaClass(className, filePath, content, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Java parser
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parseJavaClass(String className, String filePath, String content, int depth) {
        // Find class declaration and parent
        Matcher classMatcher = JAVA_CLASS_PATTERN.matcher(content);
        String parentClass = null;
        if (classMatcher.find()) {
            parentClass = classMatcher.group(2);  // may be null
        }

        // Collect annotations preceding each field
        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");
        List<String> pendingAnnotations = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            // Collect annotations
            Matcher annoMatcher = JAVA_ANNOTATION_PATTERN.matcher(trimmed);
            if (trimmed.startsWith("@") && annoMatcher.find()) {
                String anno = annoMatcher.group(1);
                String params = annoMatcher.group(2);
                pendingAnnotations.add(params != null ? anno + "(" + params.trim() + ")" : anno);
                continue;
            }

            // Skip annotations that aren't validation-related
            if (trimmed.startsWith("@")) {
                pendingAnnotations.clear();
                continue;
            }

            // Match field declarations
            Matcher fieldMatcher = JAVA_FIELD_PATTERN.matcher(trimmed);
            if (fieldMatcher.find()) {
                String type = fieldMatcher.group(1);
                String name = fieldMatcher.group(2);

                // Skip static/serialVersionUID and constants
                if (name.equals("serialVersionUID") || name.equals("serialVersionUID")) continue;
                if (trimmed.contains("static final") || trimmed.contains("static final")) continue;

                String jsonType = mapJavaType(type);
                fields.add(new FieldInfo(name, type, jsonType, List.copyOf(pendingAnnotations), className));
                pendingAnnotations.clear();
            } else if (!trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                // Non-comment, non-annotation line — clear pending
                if (!trimmed.isEmpty()) {
                    pendingAnnotations.clear();
                }
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Python parser
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parsePythonClass(String className, String filePath, String content, int depth) {
        Matcher classMatcher = PY_CLASS_PATTERN.matcher(content);
        String parentClass = null;
        if (classMatcher.find()) {
            String parents = classMatcher.group(2);
            if (parents != null && !parents.isBlank()) {
                // Take first parent (skip mixins)
                parentClass = parents.split(",")[0].trim();
                if (parentClass.equals("BaseModel") || parentClass.equals("object")) parentClass = null;
            }
        }

        List<FieldInfo> fields = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        String[] lines = content.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // Collect decorators
            Matcher annoMatcher = PY_ANNOTATION_PATTERN.matcher(trimmed);
            if (trimmed.startsWith("@") && annoMatcher.find()) {
                String anno = annoMatcher.group(1);
                String params = annoMatcher.group(2);
                pendingAnnotations.add(params != null ? anno + "(" + params.trim() + ")" : anno);
                continue;
            }
            if (trimmed.startsWith("@")) {
                pendingAnnotations.clear();
                continue;
            }

            // Match field annotations (name: Type)
            Matcher fieldMatcher = PY_FIELD_PATTERN.matcher(trimmed);
            if (fieldMatcher.find() && !trimmed.startsWith("def ") && !trimmed.startsWith("class ")) {
                String name = fieldMatcher.group(1);
                String type = fieldMatcher.group(2);

                if (name.startsWith("_")) continue;  // skip private

                String jsonType = mapPythonType(type);
                fields.add(new FieldInfo(name, type, jsonType, List.copyOf(pendingAnnotations), className));
                pendingAnnotations.clear();
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                pendingAnnotations.clear();
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  JS/TS parser
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parseTsClass(String className, String filePath, String content, int depth) {
        Matcher classMatcher = TS_CLASS_PATTERN.matcher(content);
        String parentClass = null;
        if (classMatcher.find()) {
            parentClass = classMatcher.group(2);  // may be null
        }

        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            Matcher fieldMatcher = TS_FIELD_PATTERN.matcher(trimmed);
            if (fieldMatcher.find() && !trimmed.startsWith("//")) {
                String name = fieldMatcher.group(1);
                String type = fieldMatcher.group(2);

                if (name.startsWith("_") || name.startsWith("this.")) continue;
                if (type.equals("function") || type.equals("void")) continue;

                String jsonType = mapTsType(type);
                fields.add(new FieldInfo(name, type, jsonType, List.of(), className));
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  C# parser
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parseCSharpClass(String className, String filePath, String content, int depth) {
        Matcher classMatcher = CSHARP_CLASS_PATTERN.matcher(content);
        String parentClass = null;
        if (classMatcher.find()) {
            parentClass = classMatcher.group(2);  // may be null
            if ("object".equals(parentClass)) parentClass = null;
        }

        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");
        List<String> pendingAnnotations = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            // Collect [Required], [StringLength(50)], etc.
            Matcher annoMatcher = CSHARP_ANNOTATION_PATTERN.matcher(trimmed);
            if (trimmed.startsWith("[") && annoMatcher.find()) {
                String anno = annoMatcher.group(1);
                String params = annoMatcher.group(2);
                pendingAnnotations.add(params != null ? anno + "(" + params.trim() + ")" : anno);
                continue;
            }
            if (trimmed.startsWith("[")) {
                pendingAnnotations.clear();
                continue;
            }

            // Properties: public string Name { get; set; }
            Matcher propMatcher = CSHARP_PROP_PATTERN.matcher(trimmed);
            if (propMatcher.find()) {
                String type = propMatcher.group(1);
                String name = propMatcher.group(2);
                String jsonType = mapCSharpType(type);
                fields.add(new FieldInfo(name, type, jsonType, List.copyOf(pendingAnnotations), className));
                pendingAnnotations.clear();
                continue;
            }

            // Fields: private string _name;
            Matcher fieldMatcher = CSHARP_FIELD_PATTERN.matcher(trimmed);
            if (fieldMatcher.find()) {
                String type = fieldMatcher.group(1);
                String name = fieldMatcher.group(2);
                if (name.startsWith("_")) continue;  // skip backing fields
                String jsonType = mapCSharpType(type);
                fields.add(new FieldInfo(name, type, jsonType, List.copyOf(pendingAnnotations), className));
                pendingAnnotations.clear();
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                pendingAnnotations.clear();
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  C++ parser (class + struct)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parseCppClass(String className, String filePath, String content, int depth) {
        // Try class first, then struct
        Matcher classMatcher = CPP_CLASS_PATTERN.matcher(content);
        Matcher structMatcher = CPP_STRUCT_PATTERN.matcher(content);

        String parentClass = null;
        boolean found = false;
        if (classMatcher.find()) {
            parentClass = classMatcher.group(2);
            found = true;
        } else if (structMatcher.find()) {
            parentClass = structMatcher.group(2);
            found = true;
        }

        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");

        // Track access level (public/protected/private)
        String currentAccess = "private";  // default for class
        for (String line : lines) {
            String trimmed = line.trim();

            // Track access specifiers
            if (trimmed.equals("public:")) { currentAccess = "public"; continue; }
            if (trimmed.equals("protected:")) { currentAccess = "protected"; continue; }
            if (trimmed.equals("private:")) { currentAccess = "private"; continue; }

            // Skip private members
            if (!currentAccess.equals("public")) continue;

            // Match field declarations
            Matcher fieldMatcher = CPP_FIELD_PATTERN.matcher(trimmed);
            if (fieldMatcher.find()) {
                String type = fieldMatcher.group(1);
                String name = fieldMatcher.group(2);

                // Skip methods (has parentheses), static, const
                if (trimmed.contains("(") || trimmed.contains("virtual")) continue;
                if (name.startsWith("m_") || name.startsWith("_")) continue;

                String jsonType = mapCppType(type);
                fields.add(new FieldInfo(name, type, jsonType, List.of(), className));
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  C parser (struct only, no classes in C)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parseCStruct(String className, String filePath, String content, int depth) {
        // C structs don't have inheritance, so no parent
        Matcher structMatcher = C_STRUCT_PATTERN.matcher(content);
        if (!structMatcher.find()) {
            return new ClassInfo(className, filePath, null, List.of(), depth);
        }

        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");
        boolean inStruct = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.matches("struct\\s+" + className + "\\s*\\{")) {
                inStruct = true;
                continue;
            }
            if (inStruct && trimmed.equals("};")) break;

            if (inStruct) {
                Matcher fieldMatcher = C_FIELD_PATTERN.matcher(trimmed);
                if (fieldMatcher.find()) {
                    String type = fieldMatcher.group(1);
                    String name = fieldMatcher.group(2);

                    // Skip function pointers
                    if (trimmed.contains("(*")) continue;

                    String jsonType = mapCType(type);
                    fields.add(new FieldInfo(name, type, jsonType, List.of(), className));
                }
            }
        }

        return new ClassInfo(className, filePath, null, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  PHP parser
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parsePhpClass(String className, String filePath, String content, int depth) {
        Matcher classMatcher = PHP_CLASS_PATTERN.matcher(content);
        String parentClass = null;
        if (classMatcher.find()) {
            parentClass = classMatcher.group(2);  // may be null
        }

        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");
        List<String> pendingAnnotations = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            // Collect PHP 8 attributes: #[Required], #[StringLength(50)]
            Matcher annoMatcher = PHP_ANNOTATION_PATTERN.matcher(trimmed);
            if (trimmed.startsWith("#[") && annoMatcher.find()) {
                String anno = annoMatcher.group(1);
                String params = annoMatcher.group(2);
                pendingAnnotations.add(params != null ? anno + "(" + params.trim() + ")" : anno);
                continue;
            }
            // Collect docblock @var annotations
            if (trimmed.startsWith("* @var ")) {
                // @var string — type hint from docblock
                continue;
            }
            if (trimmed.startsWith("#[")) {
                pendingAnnotations.clear();
                continue;
            }

            // Match field declarations: public string $name; or public ?int $age;
            Matcher fieldMatcher = PHP_FIELD_PATTERN.matcher(trimmed);
            if (fieldMatcher.find()) {
                String type = fieldMatcher.group(1);  // may be null for untyped
                String name = fieldMatcher.group(2);

                if (name.startsWith("_")) continue;  // skip private convention

                String fieldType = type != null ? type : "mixed";
                String jsonType = mapPhpType(fieldType);
                fields.add(new FieldInfo(name, fieldType, jsonType, List.copyOf(pendingAnnotations), className));
                pendingAnnotations.clear();
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")
                    && !trimmed.startsWith("*") && !trimmed.startsWith("#[")) {
                pendingAnnotations.clear();
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Rust parser (struct — no class inheritance in Rust)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ClassInfo parseRustStruct(String className, String filePath, String content, int depth) {
        // Rust doesn't have class inheritance, but we can look for trait impls
        // For now, parse struct fields (Rust uses composition, not inheritance)
        String parentClass = null;

        List<FieldInfo> fields = new ArrayList<>();
        String[] lines = content.split("\n");
        boolean inStruct = false;
        int braceDepth = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // Detect struct start (named fields)
            Matcher structMatcher = RUST_STRUCT_PATTERN.matcher(trimmed);
            if (structMatcher.find() && structMatcher.group(1).equals(className)) {
                inStruct = true;
                braceDepth = 0;
                // Count braces on the same line
                for (char c : trimmed.toCharArray()) {
                    if (c == '{') braceDepth++;
                    if (c == '}') braceDepth--;
                }
                if (braceDepth <= 0 && trimmed.contains("}")) {
                    inStruct = false;  // single-line struct
                }
                continue;
            }

            // Tuple struct: struct Point(f64, f64);
            Matcher tupleMatcher = RUST_TUPLE_STRUCT_PATTERN.matcher(trimmed);
            if (tupleMatcher.find() && tupleMatcher.group(1).equals(className)) {
                String typesStr = tupleMatcher.group(2);
                String[] types = typesStr.split(",");
                for (int i = 0; i < types.length; i++) {
                    String type = types[i].trim();
                    String jsonType = mapRustType(type);
                    fields.add(new FieldInfo("field" + i, type, jsonType, List.of(), className));
                }
                return new ClassInfo(className, filePath, null, fields, depth);
            }

            if (inStruct) {
                for (char c : trimmed.toCharArray()) {
                    if (c == '{') braceDepth++;
                    if (c == '}') braceDepth--;
                }
                if (braceDepth <= 0 && trimmed.contains("}")) {
                    inStruct = false;
                    // Process last line before closing
                }

                // Match field: pub name: String, or name: Option<Vec<u8>>,
                Matcher fieldMatcher = RUST_FIELD_PATTERN.matcher(trimmed);
                if (fieldMatcher.find()) {
                    String name = fieldMatcher.group(1);
                    String type = fieldMatcher.group(2);

                    // Skip pub(crate), pub(super) etc.
                    if (trimmed.startsWith("pub(") && !trimmed.startsWith("pub ")) {
                        // Check if it's pub(crate) — still include it
                    }

                    // Skip methods (has -> return type or fn keyword)
                    if (trimmed.contains("fn ") || trimmed.contains("->")) continue;

                    String jsonType = mapRustType(type);
                    fields.add(new FieldInfo(name, type, jsonType, List.of(), className));
                }

                if (!inStruct) break;
            }
        }

        return new ClassInfo(className, filePath, parentClass, fields, depth);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Type mapping
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String mapJavaType(String type) {
        // Strip generics: List<String> → List, Map<String, Object> → Map
        String base = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        base = base.trim();

        // Array: String[] → array
        if (base.endsWith("[]")) return "array";

        // Collection types
        if (base.equals("List") || base.equals("Set") || base.equals("Collection")
                || base.equals("ArrayList") || base.equals("HashSet")) return "array";
        if (base.equals("Map") || base.equals("HashMap") || base.equals("LinkedHashMap")
                || base.equals("JsonObject") || base.equals("ObjectNode")) return "object";

        return JAVA_TYPE_MAP.getOrDefault(base, "object");
    }

    private String mapPythonType(String type) {
        return switch (type.toLowerCase()) {
            case "str" -> "string";
            case "int" -> "integer";
            case "float", "decimal" -> "number";
            case "bool" -> "boolean";
            case "list", "set", "tuple", "frozenset" -> "array";
            case "dict", "mapping" -> "object";
            case "datetime", "date", "time" -> "string";
            default -> "object";
        };
    }

    private String mapTsType(String type) {
        return switch (type.toLowerCase()) {
            case "string" -> "string";
            case "number", "bigint" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object", "record", "map" -> "object";
            case "date" -> "string";
            default -> "object";
        };
    }

    private String mapCSharpType(String type) {
        // Strip nullable: int? → int, List<string>? → List
        String base = type.endsWith("?") ? type.substring(0, type.length() - 1) : type;
        // Strip generics: List<string> → List, Dictionary<string,int> → Dictionary
        if (base.contains("<")) base = base.substring(0, base.indexOf('<'));
        // Strip array: string[] → string
        if (base.endsWith("[]")) base = base.substring(0, base.length() - 2);

        // Collection types
        if (base.equals("List") || base.equals("IList") || base.equals("IEnumerable")
                || base.equals("ICollection") || base.equals("HashSet") || base.equals("ISet")) return "array";
        if (base.equals("Dictionary") || base.equals("IDictionary")
                || base.equals("JObject") || base.equals("JToken")) return "object";

        return CSHARP_TYPE_MAP.getOrDefault(base, "object");
    }

    private String mapCppType(String type) {
        // Strip pointers/references: int* → int, string& → string
        String base = type.replace("*", "").replace("&", "").trim();
        // Strip std:: prefix
        if (base.startsWith("std::")) base = base.substring(5);
        // Strip const
        if (base.startsWith("const ")) base = base.substring(6);
        // Strip template: vector<int> → vector
        if (base.contains("<")) base = base.substring(0, base.indexOf('<'));

        // Container types
        if (base.equals("vector") || base.equals("list") || base.equals("deque")
                || base.equals("set") || base.equals("unordered_set")
                || base.equals("array") || base.equals("span")) return "array";
        if (base.equals("map") || base.equals("unordered_map")
                || base.equals("multimap") || base.equals("pair")
                || base.equals("tuple") || base.equals("variant")) return "object";

        return C_TYPE_MAP.getOrDefault(base, "object");
    }

    private String mapCType(String type) {
        // Strip pointers: int* → int, char* → char
        String base = type.replace("*", "").replace("const ", "").trim();
        // Handle arrays: char name[100] → char
        if (base.contains("[")) base = base.substring(0, base.indexOf('[')).trim();

        if (base.equals("char") || base.equals("wchar_t")) return "string";
        if (base.equals("int") || base.equals("short") || base.equals("long")
                || base.equals("int32_t") || base.equals("int64_t")
                || base.equals("int16_t") || base.equals("int8_t")
                || base.equals("uint32_t") || base.equals("uint64_t")
                || base.equals("size_t") || base.equals("ssize_t")) return "integer";
        if (base.equals("float") || base.equals("double")) return "number";
        if (base.equals("bool") || base.equals("_Bool")) return "boolean";

        return "object";  // struct or unknown type
    }

    private String mapPhpType(String type) {
        // Strip nullable: ?string → string
        String base = type.startsWith("?") ? type.substring(1) : type;
        base = base.toLowerCase();

        return switch (base) {
            case "string" -> "string";
            case "int", "integer" -> "integer";
            case "float", "double" -> "number";
            case "bool", "boolean" -> "boolean";
            case "array" -> "array";
            case "object", "stdclass", "mixed" -> "object";
            case "datetime", "datetimeimmutable", "carbon" -> "string";
            default -> "object";
        };
    }

    private String mapRustType(String type) {
        // Strip Option<T>, Box<T>, Rc<T>, Arc<T>, Vec<T>, &T
        String base = type;
        if (base.startsWith("&")) base = base.substring(1).trim();
        if (base.startsWith("mut ")) base = base.substring(4).trim();

        // Unwrap wrapper types: Option<String> → String
        if (base.startsWith("Option<") || base.startsWith("Box<")
                || base.startsWith("Rc<") || base.startsWith("Arc<")
                || base.startsWith("Vec<")) {
            int start = base.indexOf('<');
            int end = base.lastIndexOf('>');
            if (start > 0 && end > start) {
                base = base.substring(start + 1, end).trim();
            }
        }

        // Strip array syntax: [u8; 1024] → u8
        if (base.startsWith("[")) {
            int semicolon = base.indexOf(';');
            if (semicolon > 0) base = base.substring(1, semicolon).trim();
        }

        return switch (base) {
            case "String", "str", "char", "&str" -> "string";
            case "i8", "i16", "i32", "i64", "i128", "isize" -> "integer";
            case "u8", "u16", "u32", "u64", "u128", "usize" -> "integer";
            case "f32", "f64" -> "number";
            case "bool" -> "boolean";
            case "Vec", "VecDeque", "LinkedList", "HashSet", "BTreeSet" -> "array";
            case "HashMap", "BTreeMap", "IndexMap" -> "object";
            default -> "object";
        };
    }
}
