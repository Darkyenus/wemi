package wemiplugin.intellij.instrumentation;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Utility methods that do class instrumentation and generation:
 * - adding null checks to annotated methods
 * - compiling forms
 */
public interface Instrumentation {

	boolean instrument(
			// Compile classpath inspection
			@NotNull Path javaHome, @NotNull List<Path> compilationClasspath,
			// Not null instrumentation
			@NotNull List<Path> notNullClasses, @NotNull List<String> notNullClassSkipPatterns, @NotNull String[] notNullAnnotations,
			// Form compilation
			@NotNull List<Path> formFiles, @NotNull Path classRoot);

}
