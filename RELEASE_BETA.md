# Jasper Beta Release Readiness

## Release Target

Jasper Beta is scoped as a JVM bytecode compiler beta:

```text
.g4 -> ANTLR -> Jasper AST -> TypeChecker -> Kotlin IR -> JVM bytecode
```

GraalVM native-image compatibility remains a hard design constraint. The local
Windows validation environment uses Oracle GraalVM 21.0.11 and Visual Studio
2022 Build Tools.

## Ready

- Kotlin IR is the active JVM route for `compileToDir()`.
- Legacy `JvmBackend` is quarantined under `archive/`.
- Multi-file compilation works for the current tested JVM path.
- Generic function inference, erased generic calls, boxing/unboxing, interface
  dispatch, class inheritance, constructor delegation, lambdas, method
  references, enums, annotations, arrays, and core control flow are covered by
  tests.
- Root Gradle `test` passes.
- `GraalVmNativeImageTest` passes locally with `native-image` available.
- `jasper-compiler/src/main/antlr` grammar files remain unchanged.

## Beta Caveats

- GraalVM native-image CI still needs to be configured with GraalVM and MSVC.
- LLVM is deferred until self-hosting/native runtime work.
- Standard library and ecosystem packages are not part of this compiler beta.
- Cross-module packaging, `.jaso`, package manager, LSP, and formatter are
  follow-up work.
- `**`/`**=` still use compatibility preprocessing rather than token-stream
  desugaring.
