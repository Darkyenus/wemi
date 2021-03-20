package wemi.test.forked;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import wemi.test.TestParameters;
import wemi.test.TestReport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Launched by the test task in a forked process.
 * Forked process has a classpath of test classes combined with Wemi jar and JUnit launcher.
 *
 * Takes no arguments.
 *
 * Reads WHOLE stdin as [TestParameters] json.
 * Program stdout is redirected to stderr, which is also where errors are printed.
 * Whole [TestReport] is printed into stdout.
 */
public final class TestLauncher {

	/**
	 * Arbitrary non-typical byte that is emitted by the test harness, before outputting the TestReport bytes.
	 * This is done because debug tools might inject messages that would break the output.
	 */
	public static final byte MAGIC_MESSAGE_START = 14; // ASCII Shift Out
	public static final byte MAGIC_MESSAGE_END = 15; // ASCII Shift In

	/** The magic message start or end must repeat this many times to count as the delimiter. */
	public static final int MAGIC_MESSAGE_DELIMITER_REPEAT = 10;

	public static void main(String[] args) {
		int exitCode;

		try {
			final PrintStream out = System.out;
			System.setOut(System.err);

			final TestParameters testParameters = new TestParameters();
			try (DataInputStream in = new DataInputStream(System.in)) {
				testParameters.readFrom(in);
			}
			setupLogging(testParameters.javaLoggingLevel);

			final Launcher launcher = LauncherFactory.create();
			ReportBuildingListener reportBuilder = new ReportBuildingListener(testParameters.filterStackTraces);
			launcher.registerTestExecutionListeners(reportBuilder);

			final LauncherDiscoveryRequest discoveryRequest;
			{
				final LauncherDiscoveryRequestBuilder requestBuilder = new LauncherDiscoveryRequestBuilder();
				final ArrayList<DiscoverySelector> selectors = new ArrayList<>();
				for (String aPackage : testParameters.selectPackages) {
					selectors.add(DiscoverySelectors.selectPackage(aPackage));
				}
				for (String aClass : testParameters.selectClasses) {
					selectors.add(DiscoverySelectors.selectClass(aClass));
				}
				for (String method : testParameters.selectMethods) {
					selectors.add(DiscoverySelectors.selectMethod(method));
				}
				for (String resource : testParameters.selectResources) {
					selectors.add(DiscoverySelectors.selectClasspathResource(resource));
				}

				final HashSet<Path> classpathRoots = new HashSet<>();
				for (String root : testParameters.classpathRoots) {
					classpathRoots.add(Paths.get(root));
				}
				selectors.addAll(DiscoverySelectors.selectClasspathRoots(classpathRoots));
				requestBuilder.selectors(selectors);

				final ArrayList<Filter<?>> filters = new ArrayList<>();
				final List<String> classIncluded = testParameters.filterClassNamePatterns.included;
				if (!classIncluded.isEmpty()) {
					filters.add(ClassNameFilter.includeClassNamePatterns(classIncluded.toArray(new String[0])));
				}
				final List<String> classExcluded = testParameters.filterClassNamePatterns.excluded;
				if (!classExcluded.isEmpty()) {
					filters.add(ClassNameFilter.excludeClassNamePatterns(classExcluded.toArray(new String[0])));
				}

				final List<String> packagesIncluded = testParameters.filterPackages.included;
				if (!packagesIncluded.isEmpty()) {
					filters.add(PackageNameFilter.includePackageNames(packagesIncluded));
				}
				final List<String> packagesExcluded = testParameters.filterPackages.excluded;
				if (!packagesExcluded.isEmpty()) {
					filters.add(PackageNameFilter.excludePackageNames(packagesExcluded));
				}

				final List<String> tagsIncluded = testParameters.filterTags.included;
				if (!tagsIncluded.isEmpty()) {
					filters.add(TagFilter.includeTags(tagsIncluded));
				}
				final List<String> tagsExcluded = testParameters.filterTags.excluded;
				if (!tagsExcluded.isEmpty()) {
					filters.add(TagFilter.excludeTags(tagsExcluded));
				}
				requestBuilder.filters(filters.toArray(new Filter<?>[0]));

				discoveryRequest = requestBuilder.build();
			}

			launcher.execute(discoveryRequest);

			// Output
			if (!reportBuilder.isComplete()) {
				System.err.println("Test report is not complete!");
			}
			final TestReport report = reportBuilder.testReport();

			try (DataOutputStream dos = new DataOutputStream(out)) {
				for (int i = 0; i < MAGIC_MESSAGE_DELIMITER_REPEAT; i++) {
					dos.write(MAGIC_MESSAGE_START);
				}
				report.writeTo(dos);
				for (int i = 0; i < MAGIC_MESSAGE_DELIMITER_REPEAT; i++) {
					dos.write(MAGIC_MESSAGE_END);
				}
			}

			exitCode = 0;
		} catch (Throwable e) {
			System.err.println("Exception while running tests");
			e.printStackTrace(System.err);

			exitCode = 1;
		} finally {
			System.out.flush();
			System.err.flush();
		}

		System.exit(exitCode);
	}

	private static void setupLogging(Level level) {
		try {
			final Logger rootLogger = Logger.getLogger("");
			for (Handler handler : rootLogger.getHandlers()) {
				rootLogger.removeHandler(handler);
			}
			rootLogger.addHandler(new Handler() {
				@Override
				public void publish(LogRecord record) {
					final Object[] parameters = record.getParameters();
					final Throwable thrown = record.getThrown();
					final StringWriter sw = new StringWriter();
					sw.append('[').append(record.getLoggerName()).append(' ').append(record.getLevel().getName()).append("] ").append(record.getMessage());
					if (parameters != null) {
						for (Object parameter : parameters) {
							sw.append(" | ");
							try {
								sw.append(parameter == null ? "null" : parameter.toString());
							} catch (Exception ignored) {}
						}
					}
					if (thrown != null) {
						sw.append('\n');
						thrown.printStackTrace(new PrintWriter(sw));
					}
					System.err.print(sw.getBuffer());
				}

				@Override
				public void flush() {}

				@Override
				public void close() throws SecurityException {
				}
			});

			rootLogger.setLevel(level);
		} catch (Exception ignored) {}
	}
}


