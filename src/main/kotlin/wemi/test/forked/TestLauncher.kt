package wemi.test.forked

import com.darkyen.tproll.util.StringBuilderWriter
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.Filter
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestExecutionResult.Status.*
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.discovery.ClassNameFilter.excludeClassNamePatterns
import org.junit.platform.engine.discovery.ClassNameFilter.includeClassNamePatterns
import org.junit.platform.engine.discovery.DiscoverySelectors.*
import org.junit.platform.engine.discovery.PackageNameFilter.excludePackageNames
import org.junit.platform.engine.discovery.PackageNameFilter.includePackageNames
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.EngineFilter.excludeEngines
import org.junit.platform.launcher.EngineFilter.includeEngines
import org.junit.platform.launcher.TagFilter.excludeTags
import org.junit.platform.launcher.TagFilter.includeTags
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import wemi.test.*
import wemi.util.appendWithStackTrace
import wemi.util.readJson
import wemi.util.writeJson
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId

/**
 * Launched by test task in a forked process.
 * Forked process has a classpath of test classes combined with Wemi jar and JUnit launcher.
 *
 * Takes no arguments.
 *
 * Reads WHOLE stdin as [TestParameters] json.
 * Program stdout is redirected to stderr, which is also where errors are printed.
 * Whole [TestReport] json is printed into stdout.
 */
fun main(args: Array<String>) {
    var exitCode: Int

    try {
        val out = System.out
        System.setOut(System.err)

        val testParameters: TestParameters = InputStreamReader(System.`in`, Charsets.UTF_8).readJson()

        val launcher = LauncherFactory.create()
        val reportBuilder = ReportBuildingListener(testParameters.filterStackTraces)
        launcher.registerTestExecutionListeners(reportBuilder)

        val discoveryRequest = LauncherDiscoveryRequestBuilder().apply {
            val selectors = mutableListOf<DiscoverySelector>()
            selectors.addMapped(testParameters.select.uris, ::selectUri)
            selectors.addMapped(testParameters.select.files, ::selectFile)
            selectors.addMapped(testParameters.select.directories, ::selectDirectory)
            selectors.addMapped(testParameters.select.packages, ::selectPackage)
            selectors.addMapped(testParameters.select.classes, ::selectClass)
            selectors.addMapped(testParameters.select.methods, ::selectMethod)
            selectors.addMapped(testParameters.select.resources, ::selectClasspathResource)
            val classpathRoots = HashSet<Path>()
            for (root in testParameters.select.classpathRoots) {
                classpathRoots.add(Paths.get(root))
            }
            selectors.addAll(selectClasspathRoots(classpathRoots))
            selectors(selectors)

            val filters = mutableListOf<Filter<*>>()
            testParameters.filter.engines.included.ifNotEmpty { filters.add(includeEngines(it)) }
            testParameters.filter.engines.excluded.ifNotEmpty { filters.add(excludeEngines(it)) }
            testParameters.filter.classNamePatterns.included.ifNotEmpty { filters.add(includeClassNamePatterns(*it.toTypedArray())) }
            testParameters.filter.classNamePatterns.excluded.ifNotEmpty { filters.add(excludeClassNamePatterns(*it.toTypedArray())) }
            testParameters.filter.packages.included.ifNotEmpty { filters.add(includePackageNames(it)) }
            testParameters.filter.packages.excluded.ifNotEmpty { filters.add(excludePackageNames(it)) }
            testParameters.filter.tags.included.ifNotEmpty { filters.add(includeTags(it)) }
            testParameters.filter.tags.excluded.ifNotEmpty { filters.add(excludeTags(it)) }
            filters(*filters.toTypedArray())
        }.build()

        launcher.execute(discoveryRequest)

        // Output
        if (!reportBuilder.complete) {
            System.err.println("Test report is not complete!")
        }
        val report = reportBuilder.testReport()

        OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
            writer.append(TEST_LAUNCHER_OUTPUT_PREFIX)
            writer.writeJson(report, TestReport::class.java)
            writer.flush()
        }

        exitCode = 0
    } catch (e: Throwable) {
        System.err.println("Exception while running tests")
        e.printStackTrace(System.err)

        exitCode = 1
    } finally {
        System.out.flush()
        System.err.flush()
    }

    System.exit(exitCode)
}

/**
 * JUnit Platform [TestExecutionListener] for collecting test execution data for Wemi.
 */
private class ReportBuildingListener(val filterStackTraces: Boolean) : TestExecutionListener {

    private val testReport = LinkedHashMap<TestIdentifier, TestData>()
    private val startTimes = HashMap<TestIdentifier, Long>()
    var complete = false
        private set

    override fun testPlanExecutionFinished(testPlan: TestPlan?) {
        complete = true
    }

    private fun TestIdentifier.data(): TestData {
        return testReport.getOrPut(this) { TestData() }
    }

    override fun executionSkipped(testIdentifier: TestIdentifier, reason: String?) {
        testIdentifier.data().apply {
            status = TestStatus.SKIPPED
            skipReason = reason
        }
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        startTimes[testIdentifier] = System.currentTimeMillis()
    }

    private fun createStackTraceMapper(source: TestSource): ((Array<StackTraceElement>) -> List<StackTraceElement>)? {
        val className: String = when (source) {
            is MethodSource -> source.className ?: return null
            is ClassSource -> source.className ?: return null
            else -> return null
        }
        return { originalStackTrace ->
            // Filters JUnit innards
            var filtered = originalStackTrace.dropLastWhile {
                it.className != className
            }
            // Filter Assertions/Assumptions extras (it throws from deep inside and it is not relevant)
            filtered = filtered.dropWhile { it.className.startsWith("org.junit.jupiter.api.Ass") }


            if (filtered.isEmpty()) {
                originalStackTrace.toList()
            } else {
                filtered
            }
        }
    }

    override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
        testIdentifier.data().apply {
            duration = startTimes.remove(testIdentifier)?.let { System.currentTimeMillis() - it } ?: -1L
            status = when (testExecutionResult.status) {
                SUCCESSFUL -> TestStatus.SUCCESSFUL
                ABORTED -> TestStatus.ABORTED
                FAILED -> TestStatus.FAILED
                else -> throw IllegalArgumentException("unknown status: ${testExecutionResult.status}")
            }
            val throwable = testExecutionResult.throwable.orElse(null)
            if (throwable != null) {
                val filter = if (filterStackTraces && testIdentifier.source.isPresent)
                    createStackTraceMapper(testIdentifier.source.get())
                else null

                val stackTrace = StringBuilder()
                if (filter == null) {
                    throwable.printStackTrace(StringBuilderWriter(stackTrace))
                } else {
                    stackTrace.appendWithStackTrace(throwable, filter)
                }
                // Drop trailing newline/whitespace
                while (stackTrace.isNotEmpty() && stackTrace.last().isWhitespace()) {
                    stackTrace.setLength(stackTrace.length - 1)
                }
                this.stackTrace = stackTrace.toString()
            }
        }
    }

    override fun reportingEntryPublished(testIdentifier: TestIdentifier, entry: ReportEntry) {
        testIdentifier.data().apply {
            val timestamp = entry.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            reports.ensureCapacity(entry.keyValuePairs.size)
            for ((k, v) in entry.keyValuePairs) {
                reports.add(TestData.ReportEntry(timestamp, k, v))
            }
        }
    }

    fun testReport(): TestReport {
        val result = TestReport()
        testReport.forEach { k, v ->
            result[k.toWemi()] = v
        }
        return result
    }

    private fun TestIdentifier.toWemi(): wemi.test.TestIdentifier {
        return wemi.test.TestIdentifier(
                uniqueId, parentId.orElse(null), displayName,
                isTest, isContainer, tags.asSequence().map { it.name }.toSet(), source.orElse(null)?.toString())
    }
}

private inline fun <T, F> MutableCollection<T>.addMapped(from: Collection<F>, mapper: (F) -> T) {
    for (f in from) {
        this.add(mapper(f))
    }
}

private inline fun <C> C.ifNotEmpty(action: (C) -> Unit) where C : Collection<*> {
    if (this.isNotEmpty()) {
        action(this)
    }
}
