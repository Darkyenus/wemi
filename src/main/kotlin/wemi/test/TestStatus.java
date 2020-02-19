package wemi.test;

/**
 * Status of {@link TestData}.
 */
public enum TestStatus {
	/**
	 * Test/collection execution was successful.
	 *
	 * Note that collection may be successful even if tests contained were not.
	 */
	SUCCESSFUL,
	/**
	 * Aborted by the user, i.e. the test would be run, but has been stopped for some reason.
	 * This does not indicate failure nor success.
	 */
	ABORTED,
	/**
	 * Test has not been run because it was skipped for some reason.
	 * @see TestData#skipReason
	 */
	SKIPPED,
	/**
	 * Test has been run and failed.
	 * @see TestData#stackTrace
	 */
	FAILED,
	/**
	 * Test has not been run.
	 * This is not a standard result and indicates a problem somewhere.
	 */
	NOT_RUN;

	static final TestStatus[] VALUES = values();
}
