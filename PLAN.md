# Kotlin IR Mainline Plan

## Direction

The compiler mainline is:

```text
ANTLR lexer/parser -> Jasper CST -> Jasper AST -> TypeChecker -> Kotlin IR -> JVM bytecode -> GraalVM
```

The `.g4` grammar files are locked unless the user explicitly approves a
language-design change. Completion work must happen after the generated
lexer/parser: AST building, semantic analysis, Kotlin IR lowering, and backend
emission.

The final product target is JVM bytecode and/or a native binary. In the current
phase, the native path is expected to come from GraalVM native-image over the JVM
bytecode output, not from LLVM.

## Current State

- `compileToDir()` uses the Kotlin IR JVM backend exclusively (legacy `JvmBackend` quarantined to `archive/`).
- Lambda expressions are lowered to anonymous classes with an `invoke()` method,
  replacing the previous no-op emission.
- TypeChecker now validates generic type parameter bounds, where constraints,
  and type argument counts.
- TypeChecker tracks type parameters in scope so they can be referenced in
  expressions (resolves to their bound or `any`).
- Structured `CompileError`/`CompileException` hierarchy is wired through the
  full compiler pipeline: syntax → `CompileError.Syntax`, symbol → `CompileError.Symbol`,
  type → `CompileError.Type`. All errors include category tags in output.
- TypeChecker now performs type argument inference for generic function calls:
  unifies argument types against parameter types, substitutes inferred types in
  return type. `JasCall.inferredTypeArgs` propagates results to the translator.
- `get`/`set` keyword fix: `DesugaredTokenStream` converts `Get`/`Set` tokens
  to `Identifier` tokens in non-property-accessor context, allowing `get`/`set`
  as function/variable names.
- User-defined type fix: `TypeMapper.currentPkg` prepends package to non-builtin
  type names, fixing `NoClassDefFoundError` for user-defined return/field types.
- Interface dispatch fix: `interfaceDeclNames` check now also matches unqualified
  name for qualified type names, fixing `IncompatibleClassChangeError`.
- `JvmIrBackend` includes GraalVM-hostile pattern detection pass, `Signature`
  attribute emission, and supports `ClassKind` detection (`INTERFACE`/`ENUM_CLASS`/`ANNOTATION_CLASS`).
- `JvmIrBackend` emits `ACC_PUBLIC | ACC_ABSTRACT` for interface `IrSimpleFunction`,
  and auto-generates default getter/setter for property accessors without explicit body.
- TypeChecker interface subtype checking completed: `isSubtype()` in `typesCompatible()`
  handles `implements` and transitive `extends` with cycle detection.
- Interface runtime tests pass: `INVOKEINTERFACE` dispatch, abstract method skip,
  implementing-class instantiation with default constructors.
- Method reference lowering uses anonymous-class pattern (like lambdas): translator
  creates `IrClass(Lambda$N)` + `invoke()` method + `IrConstructorCall` initializer.
  `functionSymbolToOwner` map in backend resolves correct target owner for `INVOKESTATIC`.
- `++`/`--` desugaring moved from regex preprocessing to token-stream transformation
  (`DesugaredTokenStream`) preserving source positions. `**`/`**=` remains as regex.
- Standard library stubs: `print`, `println`, `toString` registered as built-in
  functions in `SymbolTable`. Backend emits correct `System.out.print`/`println`
  and `String.valueOf`/`toString` calls for any argument type.
- Error messages now include source positions: `JasNode` carries `sourceLine`/`sourceColumn`,
  threaded through all AST builder visit methods. `CompileError.Type` and
  `CompileError.Symbol` include line:col. Display format: `[CATEGORY] (line:col) msg`.
- Reference type `==`/`!=` now uses `java.util.Objects.equals()` for value equality
  (handles null safely). Primitives continue to use direct JVM comparison.
- `null` constant type changed to `Ljava/lang/Object;` for correct reference-type
  comparison path.
- Generic boxing/unboxing: `emitBoxingIfNeeded` wraps primitives in `Integer.valueOf`,
  etc. when erasure to `Object` is needed. `emitUnboxingIfNeeded` unwraps via
  `intValue()`, etc. Applied at return, variable assignment, and call argument/return sites.
- `int64` (long) support complete: `JasIntLiteral` with `L` suffix typed as `int64`,
  `widenIfNeeded` emits `i2l`/`l2i`/etc., binary op handlers compute wider type
  and widen operands individually.
- `arr.length` fixed: translator uses `intType()` not `unitType()`; backend emits
  `ARRAYLENGTH` instead of `GETFIELD` with void signature.
- Class inheritance fixed: default constructor superclass resolved from
  `classSuperclassNames` map instead of hardcoded `java/lang/Object`.
- Multi-file Kotlin IR compilation now shares top-level function symbols across
  files before lowering, so cross-file static calls preserve the correct owner
  and return descriptor.
- Root `test` is green. GraalVM native-image smoke test passes locally with
  Oracle GraalVM 21.0.11 and Visual Studio 2022 Build Tools.
- `jasper-llvm` is deferred until the self-hosting/native stage.

## Completed Milestones

1. Restored reliable Gradle test signal.
2. Removed local-machine debug writes from production code.
3. Stabilized the Kotlin IR smoke suite.
4. Switched the default JVM output path to `JvmIrBackend`.
5. Kept the legacy backend behind an explicit compatibility API.
6. Fixed `preprocessPower` regex to capture multi-character identifiers.
7. Fixed TypeChecker warnings (no silent `JasAnyType` fallback).
8. Added duplicate declaration detection to SymbolTable.
9. Added structured `CompileError` types (Syntax/Symbol/Type/Internal).
10. Added numeric widening conversion rules.
11. Implemented real lambda closure lowering (anonymous `IrClass` + `invoke()`).
12. Added TypeChecker generics validation: type parameter bounds, where
    constraints, type argument count matching.
13. Wired `CompileException`/`CompileError` through the full compiler pipeline
    (syntax parsing, symbol collection, type checking, code generation).
14. Added GraalVM-hostile pattern detection (`JvmIrBackend.checkGraalvmHostilePatterns`).
15. Fixed `JvmBackend` mutable instance state (resets all fields on `generate()`).
16. Added `JvmIrBackend` support for interface, enum, and annotation `ClassKind`
    detection with correct JVM class access flags.
17. Added enum infrastructure generation (`$VALUES`, `<clinit>`, `values()`, `valueOf()`)
    and enum constructor support (synthetic `(String, int)` prefix).
18. Added property backing field support: auto-generated default getter/setter for
    property accessors with no body; fields made `public` to support cross-class
    `GETFIELD`/`PUTFIELD` access via `IrGetField`/`IrSetField`.
19. Added `fieldOwnerMap` to track field-to-class ownership for correct `GETFIELD`/
    `PUTFIELD` owner resolution in cross-class property access.
20. Added `irMethodDescriptor` and `irConstructorDescriptor` helpers for JVM
    descriptor generation from IR declarations.
21. Added interface subtype checking in TypeChecker (`isSubtype` for `implements`
    relationships with cycle detection).
22. Added interface runtime tests: `INVOKEINTERFACE` dispatch with abstract method
    skip and default no-arg constructor generation.
24. Added method reference lowering via anonymous-class pattern (same approach as
    lambda lowering).
25. Fixed method reference `INVOKESTATIC` owner resolution: `functionSymbolToOwner`
    map built in backend's first pass, used by IrCall's `else` branch to emit
    calls to the correct target class instead of the anonymous class.
26. Added `functionSymbolToOwner` map for correct `INVOKESTATIC` target class.
27. Added generic type signature (Signature attribute) emission for classes, methods,
    and fields with type parameters (`irTypeSignature`, `buildClassSignature`, etc.).
28. Added cross-package method reference support: `test::greet` now resolves
    correctly via `isStaticFunctionRef` check on method name rather than target.
29. Quarantined legacy `JvmBackend` to `archive/JvmBackend.kt`; removed
    `compileToDirLegacy()` from `JasperCompiler.kt`.
30. Moved `++`/`--` desugaring from regex preprocessing to token-stream transformation
    via `DesugaredTokenStream` (preserves source positions).
31. Added type argument inference for generic function calls: `TypeChecker` unification
    algorithm (`unify()` + `substitute()`), `JasCall.inferredTypeArgs` side channel,
    translator `substituteIrType()`, backend `CHECKCAST` insertion for erased return types.
32. Fixed `get`/`set` keyword conflict: `DesugaredTokenStream` converts `Get`/`Set` tokens
    to `Identifier` in non-accessor contexts.
33. Fixed user-defined type package prefix: `TypeMapper.currentPkg` ensures non-builtin
    types use fully qualified names in JVM descriptors.
34. Fixed interface dispatch name resolution: `interfaceDeclNames` check matches both
    qualified and unqualified names.
35. Added 11 new edge case tests (interface, generics, method ref, constructor chaining).
36. Added GraalVM native-image smoke test (`GraalVmNativeImageTest.kt`, conditional on
    `native-image` availability).
37. Added standard library stubs: `print`, `println`, `toString` as registered built-in
    functions. Added `doubleValue`, `intValue`, `longValue`, `floatValue` for numeric
    interoperability.
38. Added reference type `==`/`!=` value equality: `emitCompare` uses
    `java.util.Objects.equals()` for reference types, handles null safely.
39. Added generic boxing/unboxing: `emitBoxingIfNeeded`/`emitUnboxingIfNeeded` for
    primitive-to-Object coercion at return, assignment, and call sites.
40. Added `int64` widening support: `L` suffix literal type inference, `widenIfNeeded`
    helper, binary operator wider-type computation.
41. Fixed `arr.length`: `ARRAYLENGTH` instruction emitted instead of `GETFIELD`.
42. Fixed class inheritance `<init>`: default constructor superclass resolved from
    `classSuperclassNames` map.
43. Fixed `nullConst()` type: changed from `unitType()` (descriptor `V`) to
    `Ljava/lang/Object;` for correct reference comparison path.
44. Added source positions to all AST nodes: `JasNode.line`/`column` threaded from
    ANTLR context; `CompileError.Type`/`Symbol` include `line:col` in display.
45. Added 12+ new edge case tests (float64, int64, nested class, string array,
    string concat, while loop, null equality, generic inference, etc.).
46. Fixed multi-file Kotlin IR lowering: top-level function symbols are
    predeclared and shared across files, preventing cross-file calls from being
    emitted with the current class owner or `void` descriptors.

## Runtime Constraints

The current route must avoid Java and Kotlin dynamic traps that make native
image generation fragile or impossible.

Rules:

1. Do not define Jasper module loading in terms of Java `ClassLoader` behavior.
2. Reflection is allowed, but it must be a Jasper-controlled capability backed
   by compiler-emitted metadata or explicit registration. Do not make normal
   dispatch, dependency discovery, or runtime metadata depend on open-ended Java
   reflection.
3. Do not introduce runtime code generation as part of normal language
   execution.
4. Prefer explicit compiler-emitted metadata over classpath scanning or
   annotation/reflection discovery.
5. Prefer static method/class/field lowering over `invokedynamic` unless a
   specific feature truly requires it and the GraalVM impact is understood.
6. Keep Kotlin IR as a compiler implementation detail. Kotlin runtime dynamics
   must not leak into Jasper language semantics.

## Remaining Work

**Goal:** complete the Kotlin IR route and stabilize for production use.

### High Priority

(none - all current Beta-blocking items completed)

### Medium Priority

1. **`**`/`**=` token-stream desugaring**: move remaining power-operator regex
   preprocessing to token-stream transformation (blocked by `typeStarRun` ambiguity).
2. **Cross-module compilation**: define package/module boundaries beyond the
   current multi-file compilation unit.
3. **GraalVM native-image CI job**: run `GraalVmNativeImageTest` in CI pipeline
   with GraalVM and the platform C/C++ toolchain installed.
4. **Grammar extensions** (requires explicit approval): support explicit type arguments
   on call sites (e.g., `pair<string,int32>(...)`), `yield` expression value.

### Deferred

4. **Self-hosting native path (LLVM)**: deferred until Jasper needs native code
   generation for self-hosting or runtime implementation.

## Architecture Summary

| Layer | Status |
|-------|--------|
| ANTLR grammar | Locked |
| `JasperAstBuilder` (ANTLR Visitor) | Complete |
| `TypeChecker` / `SymbolTable` | Complete: generics, nullability, widening, duplicate detection, overload resolution, interface subtype checking, type argument inference, source-located errors |
| `JasperToIrTranslator` | Complete: all major AST nodes, lambda/method-ref anonymous-class lowering, default constructor generation, generic type argument propagation, package-qualified type names, source positions |
| `JvmIrBackend` | Complete: IR → JVM bytecode, interface/enum/annotation class kinds, property accessors, constructor delegation, anonymous class/lambda support, GraalVM pattern detection, generic signatures, INVOKEINTERFACE dispatch, CHECKCAST/boxing for erased generics, ARRAYLENGTH, Object.equals for ref types, int64 widening, print/println built-ins |
| `JvmBackend` (legacy) | Quarantined (`archive/JvmBackend.kt`) |
| `LlvmBackend` | Deferred |
