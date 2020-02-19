package wemi.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Pure Java (with no dependencies) version of {@link StackTraceUtilKt}. */
public class StackTraceUtil {

	/**
	 * Append the given throwable to the {@link StringBuilder}.
	 *
	 * The throwable is printed with the same formatting as {@link Throwable#printStackTrace()} has,
	 * but the printed stack traces can be modified by the `stackMapper`.
	 */
	public static void appendWithStackTrace(StringBuilder sb, Throwable throwable, Function<StackTraceElement[], List<StackTraceElement>> stackTraceMapper) {
		final Set<Throwable> alreadyPrinted = Collections.newSetFromMap(new IdentityHashMap<>());
		alreadyPrinted.add(throwable);

		final List<StackTraceElement> trace = stackTraceMapper.apply(throwable.getStackTrace());
		final Throwable cause = throwable.getCause();
		final Throwable[] suppressed = throwable.getSuppressed();

		// Print our stack trace
		sb.append(throwable).append('\n');
		for (StackTraceElement traceElement : trace) {
			sb.append("\tat ").append(traceElement).append('\n');
		}

		// Print suppressed exceptions, if any
		for (Throwable se : suppressed) {
			appendAdditionalStackTrace(se, sb, trace, "Suppressed: ", "\t", alreadyPrinted, stackTraceMapper);
		}

		// Print cause, if any
		if (cause != null) {
			appendAdditionalStackTrace(cause, sb, trace, "Caused by: ", "", alreadyPrinted, stackTraceMapper);
		}
	}


	/** Print our stack trace as an enclosed exception for the specified stack trace. */
	private static void appendAdditionalStackTrace(Throwable throwable,
	                                               StringBuilder sb,
	                                               List<StackTraceElement> enclosingTrace,
	                                               String caption,
	                                               String prefix,
	                                               Set<Throwable> alreadyPrinted,
	                                               Function<StackTraceElement[], List<StackTraceElement>> stackMapper) {

		if (alreadyPrinted.contains(throwable)) {
			sb.append("\t[CIRCULAR REFERENCE:").append(throwable).append("]\n");
		} else {
			alreadyPrinted.add(throwable);

			final List<StackTraceElement> trace = stackMapper.apply(throwable.getStackTrace());
			final Throwable cause = throwable.getCause();
			final Throwable[] suppressed = throwable.getSuppressed();

			// Compute number of frames in common between this and enclosing trace
			int m = trace.size() - 1;
			int n = enclosingTrace.size() - 1;
			while (m >= 0 && n >= 0 && trace.get(m) == enclosingTrace.get(n)) {
				m--;
				n--;
			}
			int framesInCommon = trace.size() - 1 - m;

			// Print our stack trace
			sb.append(prefix).append(caption).append(throwable).append('\n');
			for (int i = 0; i < m; i++) {
				sb.append(prefix).append("\tat ").append(trace.get(i)).append('\n');
			}

			if (framesInCommon != 0) {
				sb.append(prefix).append("\t... ").append(framesInCommon).append(" more").append('\n');
			}

			// Print suppressed exceptions, if any
			for (Throwable se : suppressed) {
				appendAdditionalStackTrace(se, sb, trace, "Suppressed: ", prefix + "\t", alreadyPrinted, stackMapper);
			}

			// Print cause, if any
			if (cause != null) {
				appendAdditionalStackTrace(cause, sb, trace, "Caused by: ", prefix, alreadyPrinted, stackMapper);
			}
		}
	}
}
