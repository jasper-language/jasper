package jasper.stdlib

/**
 * Loader for the Jasper standard library v1 (first phase: function-object
 * protocols, the `Error` hierarchy, and the iteration protocol).
 *
 * The library is distributed as Jasper **source** resources under
 * `/stdlib/` on the compiler's classpath. The file list is explicit (no
 * classpath scanning) so the set is deterministic and GraalVM-native-image
 * friendly. Callers compile the returned sources together with user sources;
 * see `JasperCompiler.compileAllWithStdlib`.
 */
object StdLib {

    /**
     * Standard-library source files, in translation order. Interfaces and base
     * types come before the types that depend on them so the translator's
     * last-writer-wins member indexes end up pointing at the most concrete
     * declaration.
     */
    val FILES: List<String> = listOf(
        "Mapper.jas",
        "Predicate.jas",
        "Reducer.jas",
        "Comparator.jas",
        "Action.jas",
        "Iterator.jas",
        "Error.jas",
        "RecoverableError.jas",
        "FatalError.jas"
    )

    /** The stdlib sources keyed by their synthetic file name. */
    fun sources(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (file in FILES) {
            out[file] = read(file)
        }
        return out
    }

    private fun read(file: String): String {
        val resource = "/stdlib/$file"
        val stream = StdLib::class.java.getResourceAsStream(resource)
            ?: error("Standard library resource not found on classpath: $resource")
        return stream.use { it.readBytes().decodeToString() }
    }
}
