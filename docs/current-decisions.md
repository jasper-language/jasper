# Jasper Current Syntax Decisions

This document records the current source-of-truth decisions that supersede older notes in the chapter documents.

## Soft Keywords

`where` is a soft keyword. It is valid only in generic constraint positions:

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

The following words are reserved and not implemented:

```text
raw shared weak auto unique atomic pointer handle delete destructor locked
```

They are not valid modifiers or type modifiers.

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
    handle(e);
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
