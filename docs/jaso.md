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
