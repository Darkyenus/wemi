package wemiplugin.intellij

import Keys
import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.Value
import wemi.WemiException
import wemi.dependency
import wemi.dependency.MavenCentral
import wemi.dependency.resolveDependencyArtifacts
import wemi.util.FileSet
import wemi.util.copyRecursively
import wemi.util.ensureEmptyDirectory
import wemi.util.exists
import wemi.util.include
import wemi.util.matchingFiles
import wemi.util.name
import java.nio.file.Path

/**
 *
 */
private const val FILTER_ANNOTATION_REGEXP_CLASS = "com.intellij.ant.ClassFilterAnnotationRegexp"
private const val LOADER_REF = "java2.loader"

private val LOG = LoggerFactory.getLogger("InstrumentCode")

fun EvalScope.instrumentClasses(compileOutput:Path, instrumentationClasspath:List<Path>):Path {
	val outputDir = Keys.outputClassesDirectory.get().let { it.resolveSibling("${it.name}-instrumented") }
	outputDir.ensureEmptyDirectory()
	compileOutput.copyRecursively(outputDir)

	/*val classpath = instrumentationClasspath
	ant.taskdef(name= "instrumentIdeaExtensions", classpath= classpath.asPath, loaderref= LOADER_REF, classname= "com.intellij.ant.InstrumentIdeaExtensions")

	LOG.info("Compiling forms and instrumenting code with nullability preconditions")
	val instrumentNotNull = prepareNotNullInstrumenting(classpath)
	val sourceDirs = Keys.sources.get().matchingLocatedFiles().mapNotNullTo(HashSet()) { it.root }
	//val sourceDirs = project.files(sourceSet.allSource.srcDirs.findAll { !sourceSet.resources.contains(it) && it.exists() })
	withHeadlessAwt {
		ant.instrumentIdeaExtensions(srcdir= sourceDirs.asPath, destdir= outputDir, classpath= sourceSet.compileClasspath.asPath, includeantruntime= false, instrumentNotNull= instrumentNotNull) {
			if (instrumentNotNull) {
				ant.skip(pattern= "kotlin/Metadata")
			}
		}
	}*/
	return outputDir
}

/*private fun prepareNotNullInstrumenting(classpath:FileCollection):Boolean {
	try {
		ant.typedef(name= "skip", classpath= classpath.asPath, loaderref= LOADER_REF, classname= FILTER_ANNOTATION_REGEXP_CLASS)
	} catch (e:BuildException) {
		val cause = e.getCause()
		if (cause is ClassNotFoundException && FILTER_ANNOTATION_REGEXP_CLASS == cause.getMessage()) {
			logger.info("Old version of Javac2 is used, " +
					"instrumenting code with nullability will be skipped. Use IDEA >14 SDK (139.*) to fix this")
			return false
		} else {
			throw e
		}
	}
	return true
}*/

private inline fun <R> withHeadlessAwt(task:()->R):R {
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
				include("jdom.jar", "asm-all.jar", "asm-all-*.jar", "jgoodies-forms.jar", "forms-*.jar")
		).matchingFiles()
	}

	// And then Maven
	val compilerVersion = run {
		val version = intellijDependency.version
		if (version.isSnapshot) {
			when (version.productCode) {
				"CL" -> "CLION-"+version.asStringWithoutProductCode()
				"RD" -> "RIDER-"+version.asStringWithoutProductCode()
				"PY", "PC" -> "PYCHARM-"+version.asStringWithoutProductCode()
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
