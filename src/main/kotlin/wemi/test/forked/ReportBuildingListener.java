package wemi.test.forked;

import org.jetbrains.annotations.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import wemi.test.TestData;
import wemi.test.TestReport;
import wemi.test.TestStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static wemi.util.StackTraceUtil.appendWithStackTrace;

/** JUnit Platform [TestExecutionListener] for collecting test execution data for Wemi. */
final class ReportBuildingListener implements TestExecutionListener {

	private final boolean filterStackTraces;

	private final Map<TestIdentifier, TestData> testReport = new LinkedHashMap<>();
	private final Map<TestIdentifier, Long> startTimes = new HashMap<>();

	private boolean complete = false;

	ReportBuildingListener(boolean filterStackTraces) {
		this.filterStackTraces = filterStackTraces;
	}

	public boolean isComplete() {
		return complete;
	}

	@Override
	public void testPlanExecutionFinished(TestPlan testPlan) {
		complete = true;
	}

	private TestData getTestData(TestIdentifier identifier) {
		TestData data = testReport.get(identifier);
		if (data == null) {
			data = new TestData();
			testReport.put(identifier, data);
		}
		return data;
	}

	@Override
	public void executionSkipped(TestIdentifier testIdentifier, String reason) {
		final TestData data = getTestData(testIdentifier);
		data.status = TestStatus.SKIPPED;
		data.skipReason = reason == null ? "" : reason;
	}

	@Override
	public void executionStarted(TestIdentifier testIdentifier) {
		startTimes.put(testIdentifier, System.currentTimeMillis());
	}

	@Nullable
	private Function<StackTraceElement[], List<StackTraceElement>> createStackTraceMapper(TestSource source) {
		final String className;
		if (source instanceof MethodSource) {
			className = ((MethodSource) source).getClassName();
		} else if (source instanceof ClassSource) {
			className = ((ClassSource) source).getClassName();
		} else {
			className = null;
		}
		if (className == null) {
			return null;
		}

		return (originalStackTrace) -> {
			int begin = 0;
			int end = originalStackTrace.length;
			// Filters JUnit innards
			while (begin < end && !originalStackTrace[end - 1].getClassName().equals(className)) {
				end--;
			}

			// Filter Assertions/Assumptions extras (it throws from deep inside and it is not relevant)
			while (begin < end && originalStackTrace[begin].getClassName().startsWith("org.junit.jupiter.api.Ass")) {
				begin++;
			}

			final int length = end - begin;
			if (length <= 0) {
				// Filtered out everything, weird, keep everything
				return Arrays.asList(originalStackTrace);
			}

			final ArrayList<StackTraceElement> filtered = new ArrayList<>(length);
			//noinspection ManualArrayToCollectionCopy
			for (int i = begin; i < end; i++) {
				filtered.add(originalStackTrace[i]);
			}
			return filtered;
		};
	}

	@Override
	public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
		final TestData data = getTestData(testIdentifier);

		{// Duration
			final Long startTime = startTimes.remove(testIdentifier);
			if (startTime == null) {
				data.duration = -1L;
			} else {
				data.duration = System.currentTimeMillis() - startTime;
			}
		}

		{// Status
			final TestExecutionResult.Status status = testExecutionResult.getStatus();
			switch (status) {
				case SUCCESSFUL:
					data.status = TestStatus.SUCCESSFUL;
					break;
				case ABORTED:
					data.status = TestStatus.ABORTED;
					break;
				case FAILED:
					data.status = TestStatus.FAILED;
					break;
				default:
					throw new IllegalArgumentException("unknown status: "+status);
			}
		}

		{// Throwable
			final Throwable throwable = testExecutionResult.getThrowable().orElse(null);
			if (throwable != null) {
				final Optional<TestSource> testIdentifierSource = testIdentifier.getSource();
				final Function<StackTraceElement[], List<StackTraceElement>> stackTraceMapper;
				if (filterStackTraces && testIdentifierSource.isPresent()) {
					stackTraceMapper = createStackTraceMapper(testIdentifierSource.get());
				} else {
					stackTraceMapper = null;
				}

				final StringBuilder stackTrace = new StringBuilder();
				if (stackTraceMapper == null) {
					final StringWriter writer = new StringWriter();
					try (PrintWriter printWriter = new PrintWriter(writer)) {
						throwable.printStackTrace(printWriter);
					}
					stackTrace.append(writer.getBuffer());
				} else {
					appendWithStackTrace(stackTrace, throwable, stackTraceMapper);
				}

				// Drop trailing newline/whitespace
				while (stackTrace.length() > 0 && Character.isWhitespace(stackTrace.charAt(stackTrace.length() - 1))) {
					stackTrace.setLength(stackTrace.length() - 1);
				}
				data.stackTrace = stackTrace.toString();
			}
		}
	}

	private static final ZoneId defaultTimeZone = ZoneId.systemDefault();

	@Override
	public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
		final TestData data = getTestData(testIdentifier);

		final long timestamp = entry.getTimestamp().atZone(defaultTimeZone).toInstant().toEpochMilli();

		final Map<String, String> keyValuePairs = entry.getKeyValuePairs();
		final ArrayList<TestData.ReportEntry> reports = data.reports;
		reports.ensureCapacity(keyValuePairs.size());
		for (Map.Entry<String, String> kvEntry : keyValuePairs.entrySet()) {
			reports.add(new TestData.ReportEntry(timestamp, kvEntry.getKey(), kvEntry.getValue()));
		}
	}

	public TestReport testReport() {
		final TestReport result = new TestReport();
		for (Map.Entry<TestIdentifier, TestData> entry : testReport.entrySet()) {
			result.put(toWemi(entry.getKey()), entry.getValue());
		}
		return result;
	}

	private static wemi.test.TestIdentifier toWemi(TestIdentifier identifier) {
		final Set<TestTag> tags = identifier.getTags();
		final Set<String> wemiTags = new HashSet<>(tags.size());
		for (TestTag tag : tags) {
			wemiTags.add(tag.getName());
		}

		final TestSource testSource = identifier.getSource().orElse(null);

		return new wemi.test.TestIdentifier(
				identifier.getUniqueId(), identifier.getParentId().orElse(null), identifier.getDisplayName(),
				identifier.isTest(), identifier.isContainer(), wemiTags, testSource == null ? "null" : testSource.toString());
	}
}
