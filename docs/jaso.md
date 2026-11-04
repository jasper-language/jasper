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
- `JasoLoader.check`: L1 load steps 1 to 6a of SPEC section 12.2 (trust, platform, runtime version, dependencies, capabilities, binding) against a context supplied by the caller. A platform mismatch reports the field, the host value, and the file value.
- `JasoBinding.check`: step 6a, the syntax of `jasper.import` version 1 and its agreement with the manifest (SPEC section 9.4). Type names are checked for syntax only.
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
| `public constructor` (or the implicit one) of a `public class C`, abstract or not (an importer can only call it through `super`) | `p.C.new` | `fn p.C.new(a: int32): p.C` |

Overloads of one name all get a suffix derived from their parameter types (`p.C.m$05c31294`). The suffix does not depend on declaration order, but adding the first overload to a name does change the existing symbol. Importers resolve through the declared signature, so the suffix is opaque to them.

A top-level `public function jaso_init(): int32`, `jaso_start` or `jaso_stop` is a lifecycle export: it goes into the export table under that bare name, has no `fn` line, and is not a service. Any other shape of those three names is diagnosed, because a hook that silently never runs is hard to find.

Meaning of types in `jasper.import` (SPEC section 9.4 leaves it to the Jasper layer): a dotted name is a fully qualified type; an undotted name is a language built-in (`int32` and the other primitives, `string`, `bytes`, `regex`, `void`, `any`); arrays are `Array<T>`; `?` is nullable. Managed objects are passed as garbage-collected references with no transfer of ownership, so `own` and `borrow` do not appear. Every capability in `--requires` is written as a `cap` line.

**Diagnosed, nothing written** (the module author must change the code or remove `public`): zero or several packages in one module; a package named `std` or `std.*`; `.jass` script units; public generic classes, interfaces, and functions; public nested types; public enums and annotation types; public fields; vararg parameters and default values; function, tuple, wildcard, pointer, and reference types in a signature; a signature that names a non-public type of the module; non-ASCII names in the interface; more than 256 exports; a `jasper.import` over 64 KiB or 1024 lines; type nesting deeper than 8; metadata that SPEC section 11 forbids.

`jasper.import` version 1 has no syntax for supertypes, so an importer cannot yet learn from the file that `Square` implements `Shape`, and inherited methods are declared only on the type that defines them.

Not implemented yet: the `jasper.source` extension, namespaced manifest keys from annotations, and signing in the compiler. Apart from `jasper.import`, the Jasper reader stores extension contents as offsets and does not interpret them.

## Runtime Loading (L1, Managed Modules)

`std.jaso.JasoRuntime` carries out all of SPEC section 12.2 for Managed files. It is written in Jasper and, like the reader, is compiled on demand.

```text
read -> steps 2 to 6a -> reserve -> link -> discover -> init -> start -> running
                                                                         |
                                                                       stop (hook once)
```

- `load(bytes, granted)` returns a module object; `granted` is what the caller really grants, since the manifest only requests. A Native or Hybrid file is `platform.mismatch` here: this runtime has no native host.
- All or nothing: a failure at any step removes the reservation, leaves only an audit record, and frees the name. A hook that returns non-zero or throws fails the load, and `jaso_stop` is not called for it.
- `stop(module)` is refused while a reserved or running module depends on it, and the hook runs at most once. A failing stop hook is reported, and the module stays stopped. A stopped module keeps its name, because beta has no unload.
- `unload(module)` answers `deferred` for `plugin` and `refused` for `system`. Neither changes anything.
- `module.call(symbol, args)` calls a declared function. A method's receiver is `args[0]`. An exception thrown by module code reaches the caller unchanged. Calls already in flight are not waited for when a module stops.

**Link.** The Code payload must be a stored ZIP whose entries are unique `.class` files directly in the package named by `jasper.import`, with class version at most 52. All classes are defined but none is initialized, so no module code runs before `init`. Every `fn` must resolve to a member declared by a class of the module, with the declared parameter types, return type, and static or instance shape. A Managed file without `jasper.import`, or with no `package` line, or in a `java`, `javax`, or `std` package, is refused: SPEC section 8 allows such a file, but this runtime cannot name its classes.

**Discovery** reads the manifest and `jasper.import`. Reflection only resolves names that were declared, and nothing scans classes.

**Class visibility.** A module sees its own classes, the classes its declared dependencies make public (`type` lines and the owners of `fn` lines), and `java`, `javax`, and `std`. This follows the declared graph. It is not a security boundary: JVM code can still reach other class loaders through platform APIs.

**Source imports.** `jasperc --emit jaso --module-path <paths>` (entries separated by the platform path separator; each is a directory of `*.jaso`, not searched recursively, or a single file) resolves `import a.b.C;` and `from a.b import f;` against the modules on the path. Without `--module-path`, imports are not resolved against modules and `--depends` is not checked.

- The module is found by the package named in its `jasper.import`. Types and signatures come from `jasper.import`. Version 1 cannot say whether a type is a class or an interface, what its supertypes are, or which methods are abstract, so those come from the class files in the same module. A supertype missing from the interface is skipped and its own supertypes are used, so `Square` is a `Shape` even through a non-public class.
- Nothing from the imported module is copied: its classes stay in its own file, and the call sites link at run time. The importer needs the same interface when it runs; the runtime's link step checks only the module's own declarations.
- This is not step L1. Digests, signatures, platform, and grants are checked at load. The compiler reads the path with a structural reader of its own, because `std.jaso` would have to be compiled first.
- Every imported module must be listed in `--depends`, and so must a module whose types appear in an imported module's interface, because a module can only see classes of its declared dependencies. A listed dependency must be on the path exactly once, with a version in the declared range. The importer must also `--requires` every capability that the modules it imports directly declare with `cap`; this is a check by this compiler, and what the runtime really grants is still decided at load.
- A file on the path with the same name as the module being compiled is ignored, so an output directory can be on the path. Any other unreadable file on the path is an error that names the file, even if nothing imports it.

Diagnosed: a package no module provides, or several; a name the module does not export; `import a.b.*` on a module package; undeclared modules and capabilities; arguments that do not match a constructor, static method, method, function, or `super(...)` of the interface; `new` of an interface or abstract class, or of a class with no constructor in its interface; a file that uses a module function without importing it.

Limits of this beta: a top-level function cannot be imported under another name (types can, with `from a.b import C as D`), and its simple name must be unique in the compilation, because the function table is global by simple name. Plain programs (no `--emit jaso`) have no module path, so a program that imports modules has to be a module itself; see below for how it runs. Method overloads across modules are by parameter count, as in the language; a concrete subclass that leaves an abstract superclass method unimplemented is not diagnosed; `final` on a class is not enforced by this compiler.

**Running a program.** A program that imports modules is a module with an entry point, and it runs with:

```bash
jasperc --run [--module-path <paths>] [--grant <capability>]... [--trust <name>:<namespace>:<hexkey>]... [--allow-unsigned] <module> [args]...
```

- The entry point is a top-level `public function main()` or `main(args: string[])` in the module's package, returning `void` or `int32`. The `int32` result is the exit code. It is found in `jasper.import`, like every other service. A module with several `main` overloads is refused.
- Everything after the module name goes to the program, including arguments that look like options. Write `--` before a module name that starts with `-`.
- Exit codes: the result of `main`, or 0 for `void`; 1 if `main` throws, or if it returned 0 and a stop hook failed; 2 for a usage error; 3 if the load is refused. A non-zero result from `main` is not replaced by a stop failure.
- After `main` returns or throws, all running modules stop in the reverse of their load order, so dependents stop first.
- Trust is enforced by default. `--allow-unsigned` is development mode, and it only relaxes unsigned files: a signed file must still pass every check. Capabilities are granted only by `--grant`, and the same grants apply to every module this run loads.
- `--run` compiles the standard library and `std.jaso` at each start, because they are not yet shipped precompiled. That costs a few seconds per start.

**The runtime module path.** `JasoModulePath.scan` reads the path by the same rules as `--module-path` in the compiler: a directory contributes its `*.jaso` files, not recursively, or an entry names a single file, and any unreadable file on the path is an error naming that file. The difference is that the runtime looks modules up by module name, where the compiler looks them up by package. A name that appears twice is an error only when it is needed.

`JasoRuntime.loadFromPath(path, name, granted)` first computes a load order from the manifests, with dependencies first, and then loads each module with `load`:

- The plan runs no module code, so a missing dependency, a version outside the declared range, a cycle, a stopped module, or a duplicate name is reported before any `init` runs. The plan is not the final decision, because the running set can change afterwards; `reserve` still decides.
- A module that is already running is used as it is and is not replaced by a file on the path. Its version must still fit the range, and its own dependencies are not looked at again.
- If a later load fails, dependencies that already started stay running. Stopping them would make a retry impossible, because a stopped module keeps its name. A second call skips them.
- If another thread is loading the same dependency, a finished load is used, and a load still in progress gives `module.duplicate` for the caller to retry. The runtime does not wait, because the other thread's `init` may block.
- Bytes are read once per scan, so the plan and the load see the same file. A new scan sees modules added to the path since the last one.

**Codes.** Steps 1 to 6a report the codes of SPEC section 13 through `JasoError`. SPEC defines no codes for later steps, so these belong to this runtime and are reported through `JasoRuntimeError`, and they are not part of the cross-implementation table: `link.binding`, `link.payload`, `link.symbol`, `module.duplicate`, `lifecycle.init`, `lifecycle.start`, `stop.unknown`, `stop.already`, `stop.dependent`, `stop.hook`, `module.stopped`, `module.symbol`, `module.argument`, `path.file`, `path.ambiguous`, `path.missing`, `path.version`, `path.stopped`, `path.cycle`, `entry.missing`, `entry.shape`, `program.usage`.

The audit log keeps the most recent 1024 lines, and its total count shows whether older lines were overwritten.

The test vectors in `jasper-compiler/src/test/resources/jaso-vectors/` are unmodified copies with the source commit recorded in `SOURCE`. The tables in `jaso-diff-*.tsv.gz` hold results of the Rust reference implementation on mutated inputs; `verification/jaso-diff` regenerates them, and its `check-emit` mode checks files written by the compiler.
