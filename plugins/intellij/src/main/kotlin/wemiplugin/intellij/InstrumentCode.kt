package wemiplugin.intellij

import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.Value
import wemi.WemiException
import wemi.dependency
import wemi.dependency.MavenCentral
import wemi.dependency.resolveDependencyArtifacts
import wemi.key
import wemi.keys.externalClasspath
import wemi.keys.javaHome
import wemi.keys.outputClassesDirectory
import wemi.keys.scopesCompile
import wemi.keys.sources
import wemi.util.EnclaveClassLoader
import wemi.util.FileSet
import wemi.util.copyRecursively
import wemi.util.ensureEmptyDirectory
import wemi.util.exists
import wemi.util.filterByExtension
import wemi.util.include
import wemi.util.matchingFiles
import wemi.util.name
import wemiplugin.intellij.instrumentation.Instrumentation
import java.net.URL
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("InstrumentCode")

val intellijInstrumentNotNullAnnotations by key("Fully qualified names of NotNull annotations to instrument", listOf("org.jetbrains.annotations.NotNull"))
val intellijInstrumentSkipClassesAnnotations by key("When adding NotNull assertions by annotation, skip classes with these annotations", listOf("kotlin.Metadata"))

fun EvalScope.instrumentClasses(compileOutput: Path, instrumentationClasspath: List<Path>):Path {
	val notNullAnnotations = intellijInstrumentNotNullAnnotations.get()

	val outputDir = outputClassesDirectory.get().let { it.resolveSibling("${it.name}-instrumented") }
	outputDir.ensureEmptyDirectory()
	compileOutput.copyRecursively(outputDir)

	val javaHome = javaHome.get().home
	val instrumentationClassName = "wemiplugin.intellij.instrumentation.InstrumentationImpl"

	val instrumentationClasspathURL = ArrayList<URL>()
	instrumentationClasspath.mapTo(instrumentationClasspathURL) { it.toUri().toURL() }
	// Does not work internally without running on a JBR, so don't bother
	/*// If java.home is JBR, add a dependency on its filesystem stuff, which may help find classes in there, but dunno. Give me back rt.jar.
	if (!Files.exists(javaHome.resolve("lib/jrt-fs.jar"))) {
		LOG.warn("Non JBR Java detected, instrumentation may not work correctly")
	}
	javaHome.resolve("lib/jrt-fs.jar").let {
		if (it.exists()) {
			instrumentationClasspathURL.add(it.toUri().toURL())
		}
	}*/
	val classLoader = EnclaveClassLoader(instrumentationClasspathURL.toTypedArray(), IntelliJ.javaClass.classLoader, instrumentationClassName)
	val instrumentation = Class.forName(instrumentationClassName, true, classLoader).newInstance() as Instrumentation

	val classFiles = FileSet(outputDir, include("**.class"), caseSensitive = false).matchingFiles()
	val formFiles = sources.get()?.filterByExtension("form").matchingFiles()

	// The classpath searched when instrumenting, the classpath of the plugin when running
	val compilationClasspath = ArrayList<Path>()
	compilationClasspath.addAll(outputDir)
	externalClasspath.getLocatedPathsForScope(scopesCompile.get()).mapTo(compilationClasspath) { it.classpathEntry }

	val notNullSkipAnnotations = intellijInstrumentSkipClassesAnnotations.get()

	val success = withHeadlessAwt {
		instrumentation.instrument(
				javaHome, compilationClasspath,
				classFiles, notNullSkipAnnotations, notNullAnnotations.toTypedArray(),
				formFiles, outputDir)
	}

	if (!success) {
		throw WemiException("Instrumentation failed", showStacktrace = false)
	}

	return outputDir
}

private inline fun <R> withHeadlessAwt(task: () -> R):R {
	val headlessOldValue = System.setProperty("java.awt.headless", "true")
	try {
		return task()
	} finally {
		if (headlessOldValue != null) {
			System.setProperty("java.awt.headless", headlessOldValue)
		} else {
			System.clearProperty("java.awt.headless")
		}
	}
}

val DefaultInstrumentationClasspath:Value<List<Path>> = v@{
	// Try local installation first
	val intellijDependency = IntelliJ.intellijResolvedIdeDependency.get()
	val intellijLib = intellijDependency.homeDir.resolve("lib")
	val javac2 = intellijLib.resolve("javac2.jar")
	if (javac2.exists()) {
		return@v FileSet(intellijLib,
				include("javac2.jar"),
				include("jdom.jar"),
				include("asm-all.jar"),
				include("asm-all-*.jar"),
				include("jgoodies-forms.jar"),
				include("forms-*.jar")
		).matchingFiles()
	}

	// And then Maven
	val compilerVersion = run {
		val version = intellijDependency.version
		if (version.isSnapshot) {
			when (version.productCode) {
				"CL" -> "CLION-" + version.asStringWithoutProductCode()
				"RD" -> "RIDER-" + version.asStringWithoutProductCode()
				"PY", "PC" -> "PYCHARM-" + version.asStringWithoutProductCode()
				else -> version.asStringWithoutProductCode()
			}
		} else version.asStringWithoutProductCode()
	}
	val dependency = dependency("com.jetbrains.intellij.java", "java-compiler-ant-tasks", compilerVersion)
	val repositories = listOf(
			intellijIDERepository(IntelliJ.intellijIdeRepository.get(), intellijDependency.version.asString()),
			IntelliJThirdPartyRepo,
			MavenCentral
	)

	resolveDependencyArtifacts(listOf(dependency), repositories, progressListener)
			?: throw WemiException("Failed to resolve instrumentation artifacts $dependency from $repositories")
}
