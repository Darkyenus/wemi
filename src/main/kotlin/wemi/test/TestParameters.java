package wemi.test;

import wemi.test.forked.ForkSerialization;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Parameters for the test run, using JUnit Platform.
 * Has no dependencies - pure Java. */
public final class TestParameters {

	/** The configuration parameters to be used. */
	public final Map<String, String> configuration = new HashMap<>();

	/**
	 * Whether shown stack-traces should be filtered.
	 * This involves removing the entries that are inside the JUnit framework and don't contribute much value
	 * to the regular user.
	 *
	 * True by default.
	 */
	public boolean filterStackTraces = true;

	/** A list of fully classified packages that are to be used for test discovery */
	public final List<String> selectPackages = new ArrayList<>();
	/** A list of fully classified classes that are to be used for test discovery */
	public final List<String> selectClasses = new ArrayList<>();
	/** A list of fully classified method names that are to be used for test discovery */
	public final List<String> selectMethods = new ArrayList<>();
	/** A list of classpath resources that are to be used for test discovery */
	public final List<String> selectResources = new ArrayList<>();

	/** A list of classpath roots that are to be used for test discovery.
	 * Managed by Wemi. */
	public final List<String> classpathRoots = new ArrayList<>();


	/*
	 * Additional filtering on the selected tests.
	 *
	 * Patterns are combined using OR semantics, i.e. when at least one pattern matches,
	 * the matched item will be included/excluded from the test plan.
	 */

	/** A list of regular expressions to be matched against fully classified class names,
	 * to determine which classes should be included/excluded in the test plan. */
	public final IncludeExcludeList filterClassNamePatterns = new IncludeExcludeList();
	/** A list of fully qualified packages to be included/excluded when building the test plan.
	 * Applies to sub-packages as well. */
	public final IncludeExcludeList filterPackages = new IncludeExcludeList();
	/** A list of tags to be included/excluded when building the test plan. */
	public final IncludeExcludeList filterTags = new IncludeExcludeList();

	/** Mutable collection coupling included and excluded names/patterns. */
	public static final class IncludeExcludeList {
		/** Names/patterns that are included. OR semantics. */
		public final List<String> included = new ArrayList<>();
		/** Names/patterns that are excluded. OR semantics. */
		public final List<String> excluded = new ArrayList<>();

		/** @return true if both [included] and [excluded] are empty */
		public boolean isEmpty() {
			return included.isEmpty() && excluded.isEmpty();
		}

		public void writeTo(DataOutputStream out) throws IOException {
			ForkSerialization.writeTo(out, included);
			ForkSerialization.writeTo(out, excluded);
		}

		public void readFrom(DataInputStream in) throws IOException {
			ForkSerialization.readFrom(in, included);
			ForkSerialization.readFrom(in, excluded);
		}
	}

	public Level javaLoggingLevel = Level.INFO;

	public void writeTo(DataOutputStream out) throws IOException {
		ForkSerialization.writeTo(out, configuration);
		out.writeBoolean(filterStackTraces);

		ForkSerialization.writeTo(out, selectPackages);
		ForkSerialization.writeTo(out, selectClasses);
		ForkSerialization.writeTo(out, selectMethods);
		ForkSerialization.writeTo(out, selectResources);

		ForkSerialization.writeTo(out, classpathRoots);

		filterClassNamePatterns.writeTo(out);
		filterPackages.writeTo(out);
		filterTags.writeTo(out);
		out.writeInt(javaLoggingLevel.intValue());
	}

	public void readFrom(DataInputStream in) throws IOException {
		ForkSerialization.readFrom(in, configuration);
		filterStackTraces = in.readBoolean();

		ForkSerialization.readFrom(in, selectPackages);
		ForkSerialization.readFrom(in, selectClasses);
		ForkSerialization.readFrom(in, selectMethods);
		ForkSerialization.readFrom(in, selectResources);

		ForkSerialization.readFrom(in, classpathRoots);

		filterClassNamePatterns.readFrom(in);
		filterPackages.readFrom(in);
		filterTags.readFrom(in);
		javaLoggingLevel = Level.parse(Integer.toString(in.readInt()));
	}
}
