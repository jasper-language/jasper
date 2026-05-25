# Jasper

Jasper is a language compiler project built around a Kotlin IR backend route.

## Current Compiler Pipeline

Jasper source must flow through the following primary pipeline:

```text
Jasper source
  -> ANTLR generated lexer
  -> ANTLR generated parser
  -> Jasper CST
  -> Jasper AST
  -> TypeChecker
  -> Kotlin IR
  -> JVM bytecode backend
  -> Java 8 compatible .class files
  -> GraalVM native image compatible binary path
```

The repository also contains an LLVM backend module, but it is not part of the
current completion route:

```text
Jasper source
  -> ANTLR generated lexer/parser
  -> Jasper AST
  -> LLVM IR backend
```

LLVM is deferred until the self-hosting stage, where native code generation and
runtime layout decisions become necessary. Until then, the primary route is
`Jasper AST -> TypeChecker -> Kotlin IR -> JVM bytecode -> GraalVM`.

## Architecture

| Layer | Responsibility |
|------|----------------|
| ANTLR grammar | Defines Jasper lexical and syntax rules. |
| `JasperAstBuilder` | Converts ANTLR parse trees into the project-owned Jasper AST. |
| `TypeChecker` / `SymbolTable` | Performs semantic validation and declaration lookup. |
| `JasperToIrTranslator` | Lowers Jasper AST into Kotlin IR. |
| `JvmIrBackend` | Emits GraalVM-friendly JVM bytecode from Kotlin IR. |
| `JvmBackend` | Legacy AST-to-bytecode backend quarantined under `archive/`; not part of the release route. |
| `LlvmBackend` | Deferred native/self-hosting backend candidate. Not part of the current mainline. |

## Hard Rules

1. Use ANTLR generated lexer and parser.
2. Do not introduce a handwritten lexer or parser.
3. Do not change `.g4` grammar files without explicit user approval.
4. Treat `.g4` files as part of the language specification. Implementation
   should adapt to the grammar unless the language design itself is intentionally
   changed.
5. The compiler must use `.g4`-generated lexer/parser output as the only parsing
   entry point before lowering to Kotlin IR.
6. New JVM features should be implemented on the Kotlin IR route first:
   `Jasper AST -> Kotlin IR -> JvmIrBackend`.
7. The legacy `JvmBackend` may be used for comparison and regression diagnosis,
   but it is not part of the release route and should not receive new feature
   work unless that work is needed to diagnose Kotlin IR migration.
8. LLVM work is deferred until self-hosting or native runtime design starts.
   Current language semantics and tests should be completed against the Kotlin
   IR JVM route first.
9. The current JVM output must remain suitable for GraalVM native-image.
   Reflection may exist as a Jasper feature, but it must be explicit,
   compiler-known, and runtime-managed by Jasper. Avoid Java/Kotlin dynamic
   mechanisms that require open-ended runtime discovery, including
   reflection-dependent normal dispatch, dynamic class loading as a language
   feature, runtime code generation, and backend behavior that depends on
   `invokedynamic` when a static lowering is practical.
10. JVM and GraalVM are code generation targets, not language semantics. Jasper
    modules, types, dispatch, and metadata must be defined by Jasper's own
    frontend and runtime model, not by Java or Kotlin dynamic behavior.

## Current Completion Focus

`compileToDir()` now uses the Kotlin IR JVM route by default. This route should
emit bytecode that can run on the JVM and remain compatible with a later GraalVM
native-image step. The legacy AST-to-bytecode backend has been quarantined under
`archive/`.

## Beta Scope

The Beta release target is a JVM bytecode compiler beta:

- `.g4`/ANTLR parsing remains the only grammar entry point.
- Kotlin IR is the only active JVM compiler route.
- Multi-file JVM compilation is supported for current test-covered top-level
  functions and type declarations.
- GraalVM native-image smoke test passes locally with GraalVM 21 and Visual
  Studio Build Tools; native-image CI is still a follow-up item.
- LLVM remains deferred until self-hosting/native runtime work.

Short-term priorities:

1. Keep the full Gradle test suite green on the Kotlin IR default path.
2. Move remaining shared semantics out of backend-specific code where practical.
3. Replace compatibility-only source preprocessing with real AST or IR lowering
   when the grammar already exposes the necessary structure.
4. Expand Kotlin IR coverage for closures, generics, nullability, and structured
   error reporting.
5. Defer LLVM work until self-hosting; do not let it shape current language
   semantics or completion priorities.
6. Keep new runtime and backend features static enough for GraalVM: explicit
   metadata, explicit registration, controlled Jasper reflection, and no hidden
   classpath scanning assumptions.
