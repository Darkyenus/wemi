package wemi.test;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import wemi.test.forked.ForkSerialization;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Unique, immutable, test identifier. */
public final class TestIdentifier {

	/** ID of the test, not human readable, but unique among other [TestIdentifier]s of the run */
	@NotNull
	public final String id;

	/** ID of the parent {@link TestIdentifier}. Blank if no parent exists. */
	@NotNull
	public final String parentId;

	/** Name which should be displayed to the user */
	@NotNull
	public final String displayName;

	/** If this identifies a test that was executed */
	public final boolean isTest;
	/** If this identifies a collection of tests (such collection can still have {@link TestData}) */
	public final boolean isContainer;

	/** Assigned tags */
	@NotNull
	public final Set<String> tags;

	/** Source in which this test/container has been found. No content/format is guaranteed, used for debugging. May be blank. */
	@NotNull
	public final String testSource;

	public TestIdentifier(@NotNull String id, @Nullable String parentId, @NotNull String displayName, boolean isTest, boolean isContainer, @NotNull Set<String> tags, @Nullable String testSource) {
		this.id = id;
		this.parentId = parentId == null ? "" : parentId;
		this.displayName = displayName;
		this.isTest = isTest;
		this.isContainer = isContainer;
		this.tags = tags;
		this.testSource = testSource == null ? "" : testSource;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof TestIdentifier)) return false;

		TestIdentifier that = (TestIdentifier) o;

		if (!id.equals(that.id)) return false;
		return parentId.equals(that.parentId);
	}

	@Override
	public int hashCode() {
		int result = id.hashCode();
		result = 31 * result + parentId.hashCode();
		return result;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append(id).append(':').append(displayName).append('{');
		if (isTest && isContainer) {
			sb.append("test & container");
		} else if (isTest) {
			sb.append("test");
		} else if (isContainer) {
			sb.append("container");
		} else {
			sb.append("nor test nor container");
		}

		if (!tags.isEmpty()) {
			sb.append(", tags=").append(tags);
		}

		if (!testSource.isEmpty()) {
			sb.append(", source=").append(testSource);
		}
		sb.append('}');

		return sb.toString();
	}

	public void writeTo(DataOutputStream out) throws IOException {
		out.writeUTF(id);
		out.writeUTF(parentId);
		out.writeUTF(displayName);
		out.writeBoolean(isTest);
		out.writeBoolean(isContainer);
		ForkSerialization.writeTo(out, tags);
		out.writeUTF(testSource);
	}

	public static TestIdentifier readFrom(DataInputStream in) throws IOException {
		final String id = in.readUTF();
		final String parentId = in.readUTF();
		final String displayName = in.readUTF();
		final boolean isTest = in.readBoolean();
		final boolean isContainer = in.readBoolean();
		final HashSet<String> tags = new HashSet<String>();
		ForkSerialization.readFrom(in, tags);
		final String testSource = in.readUTF();
		return new TestIdentifier(id, parentId, displayName, isTest, isContainer, tags, testSource);
	}
}

