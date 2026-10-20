# Jasper Current Syntax Decisions

This document records the current source-of-truth decisions that supersede older notes in the chapter documents.

## Soft Keywords

The true soft keywords are `get`, `set`, `from`, `onComplete`, `onBreak`,
`onZeroRuns`, `where`.
The lexer defines no token for them; the parser matches an `Identifier` by text
predicate only in the relevant position, so they remain usable as ordinary
identifiers elsewhere.  `onComplete` / `onBreak` / `onZeroRuns` are only
recognized in the suffix position of a `for-in` or `while` loop.

The following are intentionally kept as **hard reserved** tokens because they
appear mid-expression and a predicate would introduce prediction uncertainty:
`as`, `cast`, `label`, `is`, `and`, `or`, `not`.

`where` is valid only in generic constraint positions:

```jasper
function sort<T>(items: T[]): T[]
where T is Comparable {
    return items;
}
```

`late` and `decorate` are not keyword syntax. Use annotations instead:

```jasper
@Late
var cache: string;

@Decorate
function f(): void { }
```

## Export And Extern

`export` and `extern` are not keyword syntax and are not reserved language keywords.

Use annotations:

```jasper
@Export
class User { }

@Extern
function nativeCall(): int32;
```

## Reserved But Not Implemented

The following words are lexer tokens with zero parser references (reserved but
not enabled):

```text
raw shared weak auto unique atomic pointer handle locked delete destructor
```

They are not valid modifiers or type modifiers. The current decision is to **not
enable a GC/ownership superset**, so they must not be described as an
"activated superset mode".

## Lambda

Lambda has one expression form:

```jasper
var f: *T = lambda () {
    return value;
};
```

Rules:

- Lambda cannot carry an identifier.
- Lambda does not declare a return type in its own syntax.
- The target type comes from the left side or checker inference.
- Jasper does not add special function-type grammar; function object protocols are standard-library types.

## Yield

`yield` and `yield*` are both reserved as generator syntax:

```jasper
yield value;
yield* otherGenerator;
```

The concrete `Generator<T>` protocol belongs to the standard library.

## Try With Resources

try-with-resources is supported:

```jasper
try (var f = open("file.txt")) {
    use(f);
} catch (e: Error) {
    onError(e);
}
```

The concrete resource protocol belongs to the standard library.

## File Structure

Jasper uses a Java-style file organization rule to keep AI-generated code structured:

- One `.jas` file contains exactly one top-level type declaration.
- The top-level declaration must be one of `class`, `interface`, `enum`, or annotation type.
- Top-level functions, top-level variables, and top-level statements are not valid source-file items.
- Public/exported API is expressed with annotations such as `@Export`, not with extra top-level declarations.

```jasper
package app.model;

@Export
public class User {
}
```

## Source Modes And `.jass`

Strict mode is the default. It is selected for `.jas` and for the
single-source compiler API unless a mode is passed explicitly:

```text
JasperCompiler.compile(source)                 -> strict
JasperCompiler.compileScript(source)           -> script
JasperCompiler.compile(source, SourceMode)     -> explicit mode
```

File-name based APIs select script mode only for `.jass`. Mode is never
inferred from source content, a file header, or a shebang.

Strict mode accepts declarations after the optional `package` / import header
and rejects executable top-level statements. Script mode accepts the same
declarations plus executable top-level statements; those statements are
normalized into one synthetic `main(): void` and enter the same AST pipeline:

```text
function twice(value: int32): int32 {
    return value * 2;
}

var result = twice(21);
println(result);
```

The mode boundary is deliberate:

- Mode does not propagate through imports. A `.jass` entry may import strict
  `.jas` modules, which remain strict.
- A strict module cannot import a `.jass` unit. Scripts are entry units, not
  reusable libraries.
- Script mode reuses the same AST, static type checker, IR, backend, standard
  library, resource rules, and capability model as strict mode.
- Script mode does not enable dynamic types, dynamic member lookup, implicit
  conversions, runtime type mutation, reflection-by-default, or a second
  interpreter.
- Scripts do not automatically receive file, network, process, or other host
  capabilities.
- Cache keys and IDE/LSP module identity must include the mode as well as the
  source path.

Variable declaration rules are identical in both modes:

```jasper
var a = 10;          // infer from the initializer
var b: int = 20;     // explicit type and initializer
var c: int;          // explicit type; may be assigned later
c = 30;
```

The following are errors:

```jasper
a = 10;              // assignment never implicitly declares a variable
var d;               // neither type nor initializer
var e = 10;
e = "text";          // inferred type cannot change
```

## `.jaso` Module Loading

`.jaso` is the planned compiled Jasper module format. It is a runtime-managed module unit, not a Java-style collection of dynamically loaded classes.

Jasper explicitly does not use Java's open-ended `ClassLoader` model as its language module model. JVM may be one execution backend, but JVM class loading must not define Jasper module semantics.

Rules:

- A `.jaso` module is verified before execution: format, ABI, runtime range, dependencies, permissions, and integrity.
- A loaded module has an explicit lifecycle: load, init, start, stop, and unload/dispose when allowed.
- Code images, exported symbols, resources, debug data, tasks, and handles belong to a runtime-tracked module object.
- Unloading must be a Jasper runtime decision based on known live references and resources, not a best-effort hope that a Java `ClassLoader` becomes collectible.
- Platform-specific carriers such as JVM bytecode, native code, or WASM are internal code images; they do not become the public module ABI.

## Current Backend Target

The current completion route is:

```text
.g4 -> ANTLR -> Jasper AST -> TypeChecker -> Kotlin IR -> JVM bytecode -> GraalVM
```

Jasper must remain suitable for either JVM execution or native binary output.
Until self-hosting/native runtime work starts, native binary output should be
approached through GraalVM native-image over the JVM bytecode backend.

This means Jasper should avoid Java/Kotlin dynamic mechanisms as language
foundations: open-ended Java reflection, dynamic class loading, runtime code
generation, classpath scanning, and hidden Kotlin runtime dispatch should not be
required for normal program execution.

Reflection may exist, but it must be Jasper-controlled: explicit, compiler-known,
and backed by emitted metadata or registration data that can survive GraalVM
native-image. It should not inherit Java's open-ended reflective lookup model.
