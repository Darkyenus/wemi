package wemi.assembly

/**
 * A script to be used in [wemi.Keys.assemblyPrependData] which launches self jar with `exec java -jar` command.
 */
val PREPEND_SCRIPT_EXEC_JAR: ByteArray = "#!/usr/bin/env sh\nexec java -XstartOnFirstThread -jar \"$0\" \"$@\"\n"
        .toByteArray(Charsets.UTF_8)
