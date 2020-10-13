package wemiplugin.teavm

import ScopeProvided
import dependency
import libraryDependencies
import org.slf4j.LoggerFactory
import org.teavm.backend.wasm.render.WasmBinaryVersion
import org.teavm.tooling.TeaVMProblemRenderer
import org.teavm.tooling.TeaVMTargetType
import org.teavm.tooling.TeaVMTool
import org.teavm.tooling.TeaVMToolException
import org.teavm.tooling.TeaVMToolLog
import org.teavm.tooling.sources.SourceFileInfo
import org.teavm.tooling.sources.SourceFileProvider
import org.teavm.vm.TeaVMOptimizationLevel
import org.teavm.vm.TeaVMPhase
import org.teavm.vm.TeaVMProgressFeedback
import org.teavm.vm.TeaVMProgressListener
import wemi.KeyDefaults
import wemi.KeyDefaults.inProjectDependencies
import wemi.Value
import wemi.WemiException
import wemi.archetype
import wemi.compile.CompilerFlag
import wemi.key
import wemi.util.LocatedPath
import wemi.util.ensureEmptyDirectory
import wemi.util.isDirectory
import wemi.util.isHidden
import wemi.util.isRegularFile
import wemi.util.lastModifiedMillis
import wemi.util.matchingLocatedFiles
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.toSafeFileName
import java.io.IOException
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import wemiplugin.teavm.TEAVM_VERSION

object TeaVMCompilerFlags {
	val minifying = CompilerFlag("teavm.minifying", "", true)
	val maxTopLevelNames = CompilerFlag("teavm.maxTopLevelNames", "", 10000)
	val properties = CompilerFlag("teavm.properties", "", emptyMap<String, String>())
	val debugInformationGenerated = CompilerFlag("teavm.debugInformationGenerated", "", false)
	val sourceMapGenerated = CompilerFlag("teavm.sourceMapGenerated", "", true)
	val sourceFilesCopied = CompilerFlag("teavm.sourceFilesCopied", "", false)
	val incremental = CompilerFlag("teavm.incremental", "", false)
	val transformers = CompilerFlag("teavm.transformers", "", emptyArray<String>())
	val targetFileName = CompilerFlag("teavm.targetFileName", "", "")
	val entryPointName = CompilerFlag("teavm.entryPointName", "", "main")
	val classesToPreserve = CompilerFlag("teavm.classesToPreserve", "", emptyArray<String>())
	val optimizationLevel = CompilerFlag("teavm.optimizationLevel", "", TeaVMOptimizationLevel.SIMPLE)
	val fastGlobalAnalysis = CompilerFlag("teavm.fastGlobalAnalysis", "", false)
	val targetType = CompilerFlag("teavm.targetType", "", TeaVMTargetType.JAVASCRIPT)
	val wasmVersion = CompilerFlag("teavm.wasmVersion", "", WasmBinaryVersion.V_0x1)
	val minHeapSize = CompilerFlag("teavm.minHeapSizeGB", "Min heap size in GB", 4)
	val longjmpSupported = CompilerFlag("teavm.longjmpSupported", "", true)
	val heapDump = CompilerFlag("teavm.heapDump", "", false)
}

class TeaVMResult(val output: List<Path>, val mapFile: Path?, val sourcesDir: Path?) {
	override fun toString(): String {
		val sb = StringBuilder()
		sb.append("TeaVMResult(")
		output.joinTo(sb, separator = ", ")
		if (mapFile != null) {
			sb.append(", mapFile=").append(mapFile)
		}
		if (sourcesDir != null) {
			sb.append(", sourcesDir=").append(sourcesDir)
		}
		sb.append(')')
		return sb.toString()
	}
}

val teavmCompile by key<TeaVMResult>("Compile classpath with TeaVM")

private val LOG = LoggerFactory.getLogger("TeaVM")

val TeaVMCompileDefault: Value<TeaVMResult> = {
	using(Configurations.compiling) {
		val cacheDir = KeyDefaults.outputClassesDirectory("teavm-cache").invoke(this)
		val outputDir = KeyDefaults.outputClassesDirectory("teavm-output").invoke(this)
		outputDir.ensureEmptyDirectory()
		val flags = Keys.compilerOptions.get()

		val classpath = LinkedHashSet<Path>()
		Keys.internalClasspath.get().mapTo(classpath) { it.classpathEntry }
		Keys.externalClasspath.get().mapTo(classpath) { it.classpathEntry }

		val tool = TeaVMTool()
		tool.setProgressListener(object : TeaVMProgressListener {
			override fun phaseStarted(p0: TeaVMPhase?, p1: Int): TeaVMProgressFeedback {
				return TeaVMProgressFeedback.CONTINUE
			}

			override fun progressReached(p0: Int): TeaVMProgressFeedback {
				return TeaVMProgressFeedback.CONTINUE
			}
		})
		val log = object : TeaVMToolLog {
			override fun warning(p0: String?) {
				LOG.warn("{}", p0)
			}

			override fun warning(p0: String?, p1: Throwable?) {
				LOG.warn("{}", p0, p1)
			}

			override fun info(p0: String?) {
				LOG.info("{}", p0)
			}

			override fun info(p0: String?, p1: Throwable?) {
				LOG.info("{}", p0, p1)
			}

			override fun error(p0: String?) {
				LOG.error("{}", p0)
			}

			override fun error(p0: String?, p1: Throwable?) {
				LOG.error("{}", p0, p1)
			}

			override fun debug(p0: String?) {
				LOG.debug("{}", p0)
			}

			override fun debug(p0: String?, p1: Throwable?) {
				LOG.debug("{}", p0, p1)
			}

		}
		tool.log = log
		val targetType = flags.getOrDefault(TeaVMCompilerFlags.targetType)
		tool.targetType = targetType
		val mainClass = Keys.mainClass.getOrElse(null)
		tool.mainClass = mainClass
		tool.setEntryPointName(flags.getOrDefault(TeaVMCompilerFlags.entryPointName))
		tool.targetDirectory = outputDir.toFile()
		val targetFileName = flags.getOrDefault(TeaVMCompilerFlags.targetFileName).let {
			var name = if (it.isBlank()) "classes" else it
			val extension = when (targetType) {
				TeaVMTargetType.JAVASCRIPT -> "js"
				TeaVMTargetType.WEBASSEMBLY -> "wasm"
				TeaVMTargetType.C -> "c"
			}
			if (!name.pathHasExtension(extension)) {
				name = "$name.$extension"
			}
			name.toSafeFileName('_').toString()
		}
		tool.targetFileName = targetFileName
		tool.classLoader = URLClassLoader(classpath.map { it.toUri().toURL() }.toTypedArray(), log.javaClass.classLoader)
		tool.optimizationLevel = flags.getOrDefault(TeaVMCompilerFlags.optimizationLevel)
		tool.isFastDependencyAnalysis = flags.getOrDefault(TeaVMCompilerFlags.fastGlobalAnalysis)

		tool.isSourceMapsFileGenerated = flags.getOrDefault(TeaVMCompilerFlags.sourceMapGenerated)
		tool.isDebugInformationGenerated = flags.getOrDefault(TeaVMCompilerFlags.debugInformationGenerated)
		val sourceFilesCopied = flags.getOrDefault(TeaVMCompilerFlags.sourceFilesCopied)
		tool.isSourceFilesCopied = sourceFilesCopied

		tool.isMinifying = flags.getOrDefault(TeaVMCompilerFlags.minifying)
		tool.setMaxTopLevelNames(flags.getOrDefault(TeaVMCompilerFlags.maxTopLevelNames))
		tool.isIncremental = flags.getOrDefault(TeaVMCompilerFlags.incremental)
		tool.transformers.addAll(flags.getOrDefault(TeaVMCompilerFlags.transformers))
		val classesToPreserve = flags.getOrDefault(TeaVMCompilerFlags.classesToPreserve)
		tool.classesToPreserve.addAll(classesToPreserve)
		tool.cacheDirectory = cacheDir.toFile()
		tool.wasmVersion = flags.getOrDefault(TeaVMCompilerFlags.wasmVersion)
		tool.setMinHeapSize(flags.getOrDefault(TeaVMCompilerFlags.minHeapSize) * 1024 * 1024)
		tool.setLongjmpSupported(flags.getOrDefault(TeaVMCompilerFlags.longjmpSupported))
		tool.setHeapDump(flags.getOrDefault(TeaVMCompilerFlags.heapDump))

		tool.properties.putAll(flags.getOrDefault(TeaVMCompilerFlags.properties))

		val sourceFileProvider = object : SourceFileProvider {

			val archives = ArrayList<ZipFile>()
			val directories = ArrayList<Path>()
			val internalSources = ArrayList<LocatedPath>()

			override fun open() {
				val paths = using(Configurations.retrievingSources) {
					Keys.externalClasspath.get()
				}
				for (path in paths) {
					val classpathEntry = path.classpathEntry
					if (classpathEntry.isDirectory()) {
						directories.add(classpathEntry)
					} else {
						val name = classpathEntry.name
						if (name.pathHasExtension("jar") || name.pathHasExtension("zip")) {
							try {
								archives.add(ZipFile(classpathEntry.toFile(), ZipFile.OPEN_READ, Charsets.UTF_8))
							} catch (e: IOException) {
								LOG.warn("Failed to open archive {} for source extraction", classpathEntry, e)
							}
						}
					}
				}

				internalSources.addAll(Keys.sources.get().matchingLocatedFiles())
				inProjectDependencies(null) {
					internalSources.addAll(Keys.sources.get().matchingLocatedFiles())
				}
			}

			private fun toSourceFileInfo(path: LocatedPath): SourceFileInfo {
				return object : SourceFileInfo {
					override fun open(): InputStream = Files.newInputStream(path.file)
					override fun lastModified(): Long = path.file.lastModifiedMillis()
				}
			}

			private fun toSourceFileInfo(path: Path): SourceFileInfo {
				return object : SourceFileInfo {
					override fun open(): InputStream = Files.newInputStream(path)
					override fun lastModified(): Long = path.lastModifiedMillis()
				}
			}

			private fun toSourceFileInfo(archive: ZipFile, entry: ZipEntry): SourceFileInfo {
				return object : SourceFileInfo {
					override fun open(): InputStream = archive.getInputStream(entry)
					override fun lastModified(): Long = entry.time
				}
			}

			override fun getSourceFile(name: String): SourceFileInfo? {
				for (path in internalSources) {
					if (path.path.equals(name, ignoreCase = true)) {
						return toSourceFileInfo(path)
					}
				}

				for (directory in directories) {
					val resolved = directory.resolve(name)
					if (resolved.isRegularFile()) {
						return toSourceFileInfo(resolved)
					}
				}

				for (archive in archives) {
					val entry = archive.getEntry(name) ?: continue
					return toSourceFileInfo(archive, entry)
				}
				return null
			}

			private val sourceExtensions = listOf("java", "kt", "scala", "clj", "groovy")
			private fun isSource(name: String): Boolean {
				return name.pathHasExtension(sourceExtensions)
			}

			fun getAllSourceFiles(): Map<String, SourceFileInfo> {
				val result = HashMap<String, SourceFileInfo>()
				for (source in internalSources) {
					if (isSource(source.path)) {
						result[source.path] = toSourceFileInfo(source)
					}
				}

				for (directory in directories) {
					for (path in Files.walk(directory)) {
						if (path.isRegularFile() && !path.isHidden()) {
							val name = directory.relativize(path).toString()
							if (isSource(name)) {
								result[name] = toSourceFileInfo(path)
							}
						}
					}
				}

				for (archive in archives) {
					for (entry in archive.entries()) {
						if (!entry.isDirectory && isSource(entry.name)) {
							result[entry.name] = toSourceFileInfo(archive, entry)
						}
					}
				}

				return result
			}

			override fun close() {
				for (archive in archives) {
					try {
						archive.close()
					} catch (e: IOException) {
						LOG.warn("Failed to close archive {}", archive, e)
					}
				}
				archives.clear()
				directories.clear()
				internalSources.clear()
			}
		}
		tool.addSourceFileProvider(sourceFileProvider)

		if (mainClass == null && classesToPreserve.isEmpty()) {
			LOG.warn("Nor mainClass nor classes to preserve specified, generated code will be probably empty")
		}

		try {
			tool.generate()
		} catch (e: TeaVMToolException) {
			LOG.debug("TeaVM compilation failure", e)
			throw WemiException("TeaVM compilation failed", e, false)
		}

		val callGraph = tool.dependencyInfo.callGraph
		val problemProvider = tool.problemProvider
		// Not using generatedFiles, because it is not fully reliable for output enumeration

		TeaVMProblemRenderer.describeProblems(callGraph, problemProvider, log)

		if (problemProvider.severeProblems.isNotEmpty()) {
			throw WemiException.CompilationException("TeaVM compilation failed")
		}

		val outputs = ArrayList<Path>()
		var map: Path? = null
		var sourcesDir: Path? = null

		for (gen in Files.list(outputDir)) {
			val name = gen.name

			if (name.pathHasExtension("teavmdbg")) {
				continue
			}

			if (map == null && (targetType == TeaVMTargetType.JAVASCRIPT) && name.pathHasExtension("js.map")) {
				map = gen
				continue
			}

			if (sourcesDir == null && (targetType == TeaVMTargetType.JAVASCRIPT || targetType == TeaVMTargetType.WEBASSEMBLY) && name.equals("src", true) && gen.isDirectory()) {
				sourcesDir = gen
				continue
			}

			outputs.add(gen)
		}

		if (sourcesDir == null && sourceFilesCopied && (targetType == TeaVMTargetType.JAVASCRIPT || targetType == TeaVMTargetType.WEBASSEMBLY)) {
			// Bug in TeaVM: on some optimization levels, source files are not copied, for some reason. Copy it ourselves.
			sourcesDir = outputDir.resolve("src").apply { ensureEmptyDirectory() }
			try {
				sourceFileProvider.open()
				var count = 0
				for ((path, file) in sourceFileProvider.getAllSourceFiles()) {
					LOG.debug("Writing: {}", path)
					val filePath = sourcesDir.resolve(path)
					file.open().use { input ->
						Files.createDirectories(filePath.parent)
						Files.newOutputStream(filePath).use { output ->
							input.copyTo(output)
						}
					}
					count++
				}
				LOG.debug("Copied {} source files manually", count)
			} finally {
				sourceFileProvider.close()
			}
		}

		val result = TeaVMResult(outputs, map, sourcesDir)
		LOG.debug("Done: {}", result)
		result
	}
}

val TeaVMProjectLayer by archetype {
	teavmCompile set TeaVMCompileDefault

	// Emulator of Java class library for TeaVM
	libraryDependencies add { dependency("org.teavm", "teavm-classlib", TEAVM_VERSION, scope = ScopeProvided) }
	// JavaScriptObjects (JSO) - a JavaScript binding for TeaVM
	libraryDependencies add { dependency("org.teavm", "teavm-jso-apis", TEAVM_VERSION, scope = ScopeProvided) }
}