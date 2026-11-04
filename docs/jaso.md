# Jasper `.jaso` Module Format

`.jaso` is the planned compiled module format for Jasper.

It is not a source file, package manager archive, installer, or renamed `.jar` / `.dll` / `.so`. A `.jaso` file is the standard input unit for the Jasper runtime loader.

## Core Decision

Jasper does not adopt Java's open-ended dynamic class loading model as its module system.

In particular:

- Jasper modules are runtime-managed module objects, not loose sets of dynamically loaded classes.
- The loader validates a module before execution.
- The runtime owns module lifecycle, permissions, dependencies, exported symbols, tasks, resources, and unload/dispose decisions.
- JVM, native, WASM, or future backends are only code image carriers inside the Jasper module contract.
- A JVM backend may emit or execute JVM bytecode, but Java `ClassLoader` behavior must not define Jasper module semantics.

## Minimal Loader Contract

Before running module code, a `.jaso` loader must be able to reject invalid modules by checking at least:

- file magic and format version
- section directory integrity
- manifest presence
- ABI compatibility
- runtime version range
- required dependencies
- required permissions
- code image availability

## Lifecycle

The runtime-visible lifecycle is:

```text
load -> init -> start -> stop -> unload/dispose
```

Unload may be refused or delayed if the runtime can still see live tasks, exported references, resources, or handles owned by the module.

## Implementation Timing

`.jaso` should not be fully implemented before the compiler has a stable IR/backend output.

Recommended order:

1. Finish AST, semantic checker, and a minimal backend.
2. Add a minimal `.jaso` writer containing header, section directory, manifest, image index, one code image, and minimal ABI metadata.
3. Add a `.jaso` reader/validator for the runtime loader.
4. Expand ABI metadata, imports/exports, permissions, debug info, signatures, and multi-image support.

## Implementation Status (format 1, beta)

The byte-level specification is `jaso/SPEC.md` in the jasper-os repository. The Jasper side has a **reader and validator**, written in Jasper as the on-demand standard library package `std.jaso` (`StdLib.jasoSources()`; it is not compiled into every program):

- `JasoFile.read`: the structural read of SPEC section 12.1, reporting the first violation with its stable code.
- `JasoLoader.check`: L1 load steps 1 to 6 of SPEC section 12.2 (trust, platform, runtime version, dependencies, capabilities) against a context supplied by the caller. A platform mismatch reports the field, the host value, and the file value.
- `JasoConformance.check`: the fixed context of SPEC section 14, returning `ok` or a code name.
- `Ed25519`: strict verification only, implemented in Jasper because JVM 8 has no built-in Ed25519; only SHA-256 and SHA-512 come from the platform. Verification handles public data, so it is not constant-time.

The compiler **writes unsigned Managed files**:

```bash
jasperc --emit jaso -o demo.jaso --name jaso-demo --version 1.2.3 [--description <text>] [--publisher <name>] [--runtime <range>] [--depends <dep>]... [--requires <capability>]... [--lifecycle plugin|system] A.jas B.jas
```

- The image holds the module's own classes as a stored ZIP (class version 52). `std` belongs to the runtime and is not shipped in the file; `core.runtime` constrains it, `>=0.1,<0.2` by default.
- Output is deterministic: the same sources and options give the same bytes, independent of argument order.
- Signing is a separate step done by the packing tool (`jasopack sign`).

**What is exported.** Only declarations written `public` belong to the module interface, as read from the source before coroutine lowering; nothing uses reflection. The interface is the `jasper.import` extension (SPEC section 9.4, critical) and the export table, which agree item by item:

| Source | Symbol | Declared as |
|---|---|---|
| top-level `public function f` in package `p` | `p.f.f` | `fn p.f.f(a: int32): int32` |
| `public static function m` in `public class C` | `p.C.m` | `fn p.C.m(a: int32): int32` |
| `public function m` in `public class C`, or a method of `public interface I` | `p.C.m` | `fn p.C.m(this: p.C, a: int32): int32` |
| `public constructor` (or the implicit one) of a non-abstract `public class C` | `p.C.new` | `fn p.C.new(a: int32): p.C` |

Overloads of one name all get a suffix derived from their parameter types (`p.C.m$05c31294`). The suffix does not depend on declaration order, but adding the first overload to a name does change the existing symbol. Importers resolve through the declared signature, so the suffix is opaque to them.

Meaning of types in `jasper.import` (SPEC section 9.4, which arrives with the jasper-os loader work and is not on its `main` yet, leaves it to the Jasper layer): a dotted name is a fully qualified type; an undotted name is a language built-in (`int32` and the other primitives, `string`, `bytes`, `regex`, `void`, `any`); arrays are `Array<T>`; `?` is nullable. Managed objects are passed as garbage-collected references with no transfer of ownership, so `own` and `borrow` do not appear. Every capability in `--requires` is written as a `cap` line.

**Diagnosed, nothing written** (the module author must change the code or remove `public`): zero or several packages in one module; a package named `std` or `std.*`; `.jass` script units; public generic classes, interfaces, and functions; public nested types; public enums and annotation types; public fields; vararg parameters and default values; function, tuple, wildcard, pointer, and reference types in a signature; a signature that names a non-public type of the module; non-ASCII names in the interface; more than 256 exports; a `jasper.import` over 64 KiB or 1024 lines; type nesting deeper than 8; metadata that SPEC section 11 forbids.

`jasper.import` version 1 has no syntax for supertypes, so an importer cannot yet learn from the file that `Square` implements `Shape`, and inherited methods are declared only on the type that defines them.

Not implemented yet: the `jasper.source` extension, namespaced manifest keys from annotations, signing in the compiler, linking and running managed images (L1 steps 7 to 9), and source `import` resolution against a `.jaso`. The Jasper reader stores extension contents as offsets and does not interpret them, so it does not check `jasper.import` (L1 step 6a) yet.

The test vectors in `jasper-compiler/src/test/resources/jaso-vectors/` are unmodified copies with the source commit recorded in `SOURCE`. The tables in `jaso-diff-*.tsv.gz` hold results of the Rust reference implementation on mutated inputs; `verification/jaso-diff` regenerates them, and its `check-emit` mode checks files written by the compiler.
