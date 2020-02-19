package wemi.test;

import wemi.WithExitCode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returned by the test.
 *
 * In execution order contains {@link TestIdentifier}s that were run and {@link TestData} informing about their execution.
 */
@SuppressWarnings("serial")
public final class TestReport extends LinkedHashMap<TestIdentifier, TestData> implements WithExitCode {

	/** Returns {@link wemi.boot.Launch#EXIT_CODE_SUCCESS} when no tests failed, {@link wemi.boot.Launch#EXIT_CODE_TASK_FAILURE} otherwise. */
	@Override
	public int processExitCode() {
		for (TestData value : values()) {
			if (value.status == TestStatus.FAILED) {
				return wemi.boot.Launch.EXIT_CODE_TASK_FAILURE;
			}
		}
		return wemi.boot.Launch.EXIT_CODE_SUCCESS;
	}

	public void writeTo(DataOutputStream out) throws IOException {
		out.writeInt(size());
		for (Map.Entry<TestIdentifier, TestData> entries : entrySet()) {
			entries.getKey().writeTo(out);
			entries.getValue().writeTo(out);
		}
	}

	public void readFrom(DataInputStream in) throws IOException {
		clear();

		final int entryCount = in.readInt();
		for (int i = 0; i < entryCount; i++) {
			final TestIdentifier identifier = TestIdentifier.readFrom(in);
			final TestData data = new TestData();
			data.readFrom(in);
			put(identifier, data);
		}
	}
}
