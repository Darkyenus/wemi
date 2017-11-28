package wemi.dependency

/**
 *
 */
@JvmName("prettyPrintFromDependency")
fun Map<DependencyId, ResolvedDependency>.prettyPrint(roots:Collection<Dependency>):CharSequence {
    return prettyPrint(roots.map { it.dependencyId })
}

/**
 * Returns a pretty-printed string in which the system is displayed as a tree of dependencies.
 * Uses full range of unicode characters for clarity.
 */
fun Map<DependencyId, ResolvedDependency>.prettyPrint(roots:Collection<DependencyId>):CharSequence {
    /*
    ╤ org.foo:proj:1.0 ✅
    │ ╘ com.bar:pr:2.0 ❌⛔️
    ╞ org.foo:proj:1.0 ✅⤴︎
    ╘ com.baz:pr:2.0 ❌⛔️

    Box drawing:
    ╤ root
    ═ single node root
    ╘ new start
    ╞ branch
    │ continuation

    Status symbols:
    OK ✅
    Error ❌⛔️
    Missing ❓
    Already shown ⤴︎
     */
    val realRoots:Collection<DependencyId> = if (roots.isEmpty()) {
        // No explicit roots, guess them
        val keys: HashSet<DependencyId> = HashSet(this.keys)
        for (resolved in this.values) {
            for (dependency in resolved.dependencies) {
                keys.remove(dependency.dependencyId)
            }
        }

        if (keys.isEmpty()) {
            // Can't guess, use everything as a root
            this.keys
        } else {
            keys
        }
    } else roots

    if (realRoots.isEmpty()) {
        return ""
    }

    val alreadyPrinted = HashSet<DependencyId>()

    val result = StringBuilder()
    val prefix = StringBuilder()

    fun DependencyId.println() {
        result.append(this.group).append(':').append(this.name).append(':').append(this.version)
        for ((key, value) in attributes) {
            result.append(' ').append(key.name).append('=').append(value)
        }
        result.append(' ')

        val firstTime = !alreadyPrinted.contains(this)
        alreadyPrinted.add(this)

        val resolved = this@prettyPrint[this]

        when {
            resolved == null -> result.append("❓")
            resolved.hasError -> result.append("❌⛔️")
            else -> result.append("✅")
        }

        val resolvedFrom = resolved?.resolvedFrom
        if (firstTime && resolvedFrom != null) {
            result.append(" from ").append(resolvedFrom)
        }

        if (!firstTime) {
            result.append(" ⤴︎\n")
        } else if (resolved == null) {
            result.append('\n')
        } else {
            result.append('\n')
            // Print hierarchy
            val prevPrefixLength = prefix.length
            val dependenciesSize = resolved.dependencies.size
            resolved.dependencies.forEachIndexed { index, dependency ->
                prefix.setLength(prevPrefixLength)
                result.append(prefix)
                if (index + 1 == dependenciesSize) {
                    result.append("╘ ")
                    prefix.append("  ")
                } else {
                    result.append("╞ ")
                    prefix.append("│ ")
                }

                dependency.dependencyId.println()
            }
            prefix.setLength(prevPrefixLength)
        }
    }

    val rootsSize = realRoots.size

    realRoots.forEachIndexed { rootIndex, root ->
        if (rootIndex + 1 == rootsSize) {
            prefix.setLength(0)
            prefix.append("  ")
        } else {
            prefix.setLength(0)
            prefix.append("│ ")
        }

        if (rootIndex == 0) {
            if (rootsSize == 1) {
                result.append("═ ")
            } else {
                result.append("╤ ")
            }
        } else if (rootIndex + 1 == rootsSize) {
            result.append("╘ ")
        } else {
            result.append("╞ ")
        }

        root.println()
    }

    return result
}