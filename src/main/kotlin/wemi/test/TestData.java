package wemi.test;

import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Results of run of test identified by {@link TestIdentifier}.
 *
 * Mutable.
 */
public final class TestData {

	/** Status of this {@link TestIdentifier}'s run */
	@NotNull
	public TestStatus status = TestStatus.NOT_RUN;

	/** Duration of the run, in ms. -1 when unknown. */
	public long duration = -1L;

	/** If {@link #status} is {@link TestStatus#SKIPPED}, this may contain why it was skipped */
	@NotNull
	public String skipReason = "";

	/** If {@link #status} is {@link TestStatus#FAILED}, this may contain the (possibly filtered) stack trace of the error */
	@NotNull
	public String stackTrace = "";

	/**
	 * Custom reports made by the test execution.
	 *
	 * Those contain user data reported by the test.
	 *
	 * See http://junit.org/junit5/docs/current/user-guide/#writing-tests-dependency-injection TestReporter.
	 */
	@NotNull
	public final ArrayList<ReportEntry> reports = new ArrayList<>();

	public static final class ReportEntry {
		public final long timestamp;
		@NotNull
		public final String key;
		@NotNull
		public final String value;

		public ReportEntry(long timestamp, @NotNull String key, @NotNull String value) {
			this.timestamp = timestamp;
			this.key = key;
			this.value = value;
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append('{').append(status);
		if (duration != -1L) {
			sb.append(" in ").append(duration).append(" ms");
		}
		if (!skipReason.isEmpty()) {
			if (status == TestStatus.SKIPPED) {
				sb.append(" reason: ");
			} else {
				sb.append(" skip reason: ");
			}
			sb.append(skipReason);
		}
		final String stackTrace = this.stackTrace;
		if (!stackTrace.isEmpty()) {
			if (stackTrace.indexOf('\n') >= 0) {
				sb.append(" exception:\n").append(stackTrace).append("\n");
			} else {
				sb.append(" exception:").append(stackTrace).append(' ');
			}
		}
		final List<ReportEntry> reports = this.reports;
		if (!reports.isEmpty()) {
			sb.append("reports=").append(reports);
		}
		sb.append('}');

		return sb.toString();
	}

	public void writeTo(DataOutputStream out) throws IOException {
		out.writeByte(status.ordinal());
		out.writeLong(duration);
		out.writeUTF(skipReason);
		out.writeUTF(stackTrace);

		out.writeInt(reports.size());
		for (ReportEntry report : reports) {
			out.writeLong(report.timestamp);
			out.writeUTF(report.key);
			out.writeUTF(report.value);
		}
	}

	public void readFrom(DataInputStream in) throws IOException {
		status = TestStatus.VALUES[in.readUnsignedByte()];
		duration = in.readLong();
		skipReason = in.readUTF();
		stackTrace = in.readUTF();

		final int reportCount = in.readInt();
		final ArrayList<ReportEntry> reports = this.reports;
		reports.clear();
		reports.ensureCapacity(reportCount);
		for (int i = 0; i < reportCount; i++) {
			final long timestamp = in.readLong();
			final String key = in.readUTF();
			final String value = in.readUTF();
			reports.add(new ReportEntry(timestamp, key, value));
		}
	}
}
