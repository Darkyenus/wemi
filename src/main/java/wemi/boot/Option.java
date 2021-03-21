package wemi.boot;

import java.io.PrintStream;
import java.util.Arrays;

/** Lightweight, GNU compatible (to some extent) command line option parsing. */
final class Option {

	static final char NO_SHORT_NAME = '\0';

	/** Short option name, if any */
	private final char shortName;
	/** Long option name, if any */
	private final String longName;

	/** Description of the option, used for printing help */
	private final String description;

	/** Whether or not this option takes an argument.
	 * true -> argument must be present
	 * false -> argument must not be present
	 * null -> argument must be present for short, may be present for long */
	private final Boolean argument;
	/** Single word description of the argument's value. Null if [argument] = false. */
	private final String argumentDescription;

	/** Function to call when this option is encountered, with */
	private final OptionHandler handler;

	Option(char shortName, String longName, String description, Boolean argument, String argumentDescription, OptionHandler handler) {
		this.shortName = shortName;
		this.longName = longName;
		this.description = description;
		this.argument = argument;
		this.argumentDescription = argumentDescription;
		this.handler = handler;
	}

	Option(char shortName, String longName, String description, OptionHandler handler) {
		this.shortName = shortName;
		this.longName = longName;
		this.description = description;
		this.argument = Boolean.FALSE;
		this.argumentDescription = null;
		this.handler = handler;
	}

	/** Print option help. */
	static void printWemiHelp(Option[] options) {
		// https://www.gnu.org/prep/standards/html_node/_002d_002dhelp.html
		final PrintStream err = System.err;
		err.println("Usage: wemi [OPTION]... [TASK]...");
		err.println("Wemi build system");
		err.println("Each task argument is treated as a separate interactive line.");

		final StringBuilder[] lines = new StringBuilder[options.length];
		for (int i = 0; i < options.length; i++) {
			lines[i] = new StringBuilder(120);
		}

		// Add options
		int maxLineLength = 0;
		for (int i = 0; i < options.length; i++) {
			final Option option = options[i];
			final StringBuilder line = lines[i];

			if (option.shortName == NO_SHORT_NAME) {
				line.append("     ");
			} else {
				line.append("  -").append(option.shortName);
				if (option.longName == null) {
					line.append(' ');
				} else {
					line.append(',');
				}
			}

			if (option.longName != null) {
				line.append(" --").append(option.longName);
				if (option.argument != Boolean.FALSE) {
					line.append('=').append(option.argumentDescription == null ? "ARG" : option.argumentDescription);
				}
			}

			if (line.length() > maxLineLength) {
				maxLineLength = line.length();
			}
		}

		// Add descriptions and print
		maxLineLength += 2; // Separate from descriptions
		for (int i = 0; i < options.length; i++) {
			final Option option = options[i];
			final StringBuilder line = lines[i];

			final int spaces = maxLineLength - line.length();
			for (int j = 0; j < spaces; j++) {
				line.append(' ');
			}
			line.append(option.description);
			err.println(line);
		}

		err.println("Wemi on Github: <https://github.com/Darkyenus/WEMI>");
	}

	/** Parse given command line options, handling them as they are encountered.
	 * @return remaining, non-option arguments or null if arguments are wrong */
	static String[] parseOptions(String[] args, Option[] options) {
		// https://www.gnu.org/software/libc/manual/html_node/Argument-Syntax.html
		final PrintStream err = System.err;
		int argsIndex = 0;
		while (argsIndex < args.length) {
			final String arg = args[argsIndex++];
			if ("--".equals(arg)) {
				// End of options
				break;
			} else if (arg.startsWith("--")) {
				// Long option
				final int equalsIndex = arg.indexOf('=');
				final String optName = equalsIndex >= 0 ? arg.substring(2, equalsIndex) : arg.substring(2);
				Option option = null;
				for (Option o : options) {
					if (optName.equals(o.longName)) {
						option = o;
						break;
					}
				}

				if (option == null) {
					err.println("wemi: unrecognized option '--"+optName+"'");
					return null;
				}

				if (option.argument == Boolean.FALSE && equalsIndex >= 0) {
					err.println("wemi: option '--"+optName+"' doesn't allow an argument");
					return null;
				}

				if (option.argument == Boolean.TRUE && equalsIndex < 0) {
					err.println("wemi: option '--"+optName+"' requires an argument");
					return null;
				}

				final String argument = equalsIndex >= 0 ? arg.substring(equalsIndex+1) : null;

				option.handler.handle(argument, options);
			} else if (arg.startsWith("-") && arg.length() > 1) {
				// Short options
				int shortOptIndex = 1;
				while (shortOptIndex < arg.length()) {
					final char optName = arg.charAt(shortOptIndex++);

					Option option = null;
					for (Option o : options) {
						if (optName == o.shortName) {
							option = o;
							break;
						}
					}

					if (option == null) {
						err.println("wemi: unrecognized option '-"+optName+"'");
						return null;
					}

					final String argument;

					if (option.argument != Boolean.FALSE) {
						if (shortOptIndex + 1 < arg.length()) {
							// Argument is without blank
							argument = arg.substring(shortOptIndex);
						} else if (argsIndex < args.length) {
							// Argument is in the next args
							argument = args[argsIndex++];
						} else {
							err.println("wemi: option '-"+optName+"' requires an argument");
							return null;
						}
						shortOptIndex = arg.length(); // No more short options in this arg
					} else {
						argument = null;
					}

					option.handler.handle(argument, options);
				}
			} else {
				// Not part of options
				argsIndex--;
				break;
			}
		}

		final int freeSize = args.length - argsIndex;
		if (freeSize <= 0) {
			return new String[0];
		}
		return Arrays.copyOfRange(args, args.length - freeSize, args.length);
	}

	public interface OptionHandler {
		/**
		 * Called when an option has been parsed.
		 * @param arg may be null, the argument parsed with the option, if any
		 * @param allOptions that are being parsed, for introspective options like --help
		 */
		void handle(String arg, Option[] allOptions);
	}
}