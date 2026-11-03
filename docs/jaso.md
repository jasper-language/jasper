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

The byte-level specification is `jaso/SPEC.md` in the jasper-os repository. The Jasper side currently has a **reader and validator**, written in Jasper as the on-demand standard library package `std.jaso` (`StdLib.jasoSources()`; it is not compiled into every program):

- `JasoFile.read`: the structural read of SPEC section 12.1, reporting the first violation with its stable code.
- `JasoLoader.check`: L1 load steps 1 to 6 of SPEC section 12.2 (trust, platform, runtime version, dependencies, capabilities) against a context supplied by the caller. A platform mismatch reports the field, the host value, and the file value.
- `JasoConformance.check`: the fixed context of SPEC section 14, returning `ok` or a code name.
- `Ed25519`: strict verification only, implemented in Jasper because JVM 8 has no built-in Ed25519; only SHA-256 and SHA-512 come from the platform. Verification handles public data, so it is not constant-time.

Not implemented yet: writing `.jaso` files, linking and running managed images (L1 steps 7 to 9), the `jasper.import` contents, and source `import` resolution. Extension contents are stored as offsets and not interpreted.

The test vectors in `jasper-compiler/src/test/resources/jaso-vectors/` are unmodified copies with the source commit recorded in `SOURCE`. The tables in `jaso-diff-*.tsv.gz` hold results of the Rust reference implementation on mutated inputs; `verification/jaso-diff` regenerates them.
