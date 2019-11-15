package wemi.boot;

import com.darkyen.tproll.TPLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Parse command line options, loads self-packed libraries and start real Main. */
@SuppressWarnings("WeakerAccess")
public class Main {

	/** Version of Wemi build system */
	public static final String WEMI_VERSION = "0.10-SNAPSHOT";

	// Standard exit codes
	public static final int EXIT_CODE_SUCCESS = 0;
	public static final int EXIT_CODE_OTHER_ERROR = 1;
	public static final int EXIT_CODE_ARGUMENT_ERROR = 2;
	public static final int EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR = 3;
	/**
	 * Exit code to be returned when all conditions for [wemi.WithExitCode] are met,
	 * but the key evaluation failed, for any reason, including unset key and evaluation throwing an exception.
	 */
	public static final int EXIT_CODE_TASK_ERROR = 4;
	/**
	 * Exit code reserved for general purpose task failure,
	 * for example to be returned by [wemi.WithExitCode.processExitCode].
	 */
	public static final int EXIT_CODE_TASK_FAILURE = 5;
	/**
	 * When Wemi exits, but wants to be restarted right after that.
	 */
	public static final int EXIT_CODE_RELOAD = 6;

	// Machine-output exit codes
	public static final int EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR = 51;
	public static final int EXIT_CODE_MACHINE_OUTPUT_NO_PROJECT_ERROR = 52;
	public static final int EXIT_CODE_MACHINE_OUTPUT_NO_CONFIGURATION_ERROR = 53;
	public static final int EXIT_CODE_MACHINE_OUTPUT_NO_KEY_ERROR = 54;
	public static final int EXIT_CODE_MACHINE_OUTPUT_KEY_NOT_SET_ERROR = 55;
	public static final int EXIT_CODE_MACHINE_OUTPUT_INVALID_COMMAND = 56;

	/* ONLY COMPILE TIME CONSTANTS ALLOWED IN THIS FILE!
	 * (because it will be reloaded and discarded) */

	static final int OPTION_PATH_ROOT_FOLDER = 0;
	static final int OPTION_PATH_BUILD_FOLDER = 1;
	static final int OPTION_PATH_CACHE_FOLDER = 2;
	static final int OPTION_BOOL_CLEAN_BUILD = 3;
	static final int OPTION_BOOL_INTERACTIVE = 4;
	static final int OPTION_BOOL_MACHINE_READABLE = 5;
	static final int OPTION_BOOL_ALLOW_BROKEN_BUILD_SCRIPTS = 6;
	static final int OPTION_BOOL_RELOAD_SUPPORTED = 7;
	static final int OPTION_LIST_OF_STRING_TASKS = 8;
	static final int OPTION_LIST_OF_PATH_RUNTIME_CLASSPATH = 9;
	static final int OPTION_LOG_LEVEL = 10;
	static final int OPTIONS_SIZE = 11;

	public static void main(String[] args) {
		final boolean[] cleanBuild = {false};
		final Boolean[] interactive = {null};
		final boolean[] machineReadableOutput = {false};
		final boolean[] allowBrokenBuildScripts = {false};
		final boolean[] reloadSupported = {false};
		final Path[] rootDirectory = {null};
		final byte[] logLevel = {-1};

		final Option[][] options = {null}; // To be accessible inside its declaration
		options[0] = new Option[]{
				new Option('c', "clean", "perform a clean rebuild of build scripts",
						false, null, arg -> cleanBuild[0] = true),
				new Option('l', "log", "set the log level (single letter variants also allowed)",
						true, "{trace|debug|info|warn|error}", level -> {
					switch (level.toLowerCase()) {
						case "trace":
						case "t":
							logLevel[0] = TPLogger.TRACE;
							break;
						case "debug":
						case "d":
							logLevel[0] = TPLogger.DEBUG;
							break;
						case "info":
						case "i":
							logLevel[0] = TPLogger.INFO;
							break;
						case "warning":
						case "warn":
						case "w":
							logLevel[0] = TPLogger.WARN;
							break;
						case "error":
						case "e":
							logLevel[0] = TPLogger.ERROR;
							break;
						default:
							System.err.println("Unknown log level: " + level);
							System.exit(EXIT_CODE_ARGUMENT_ERROR);
					}
				}),
				new Option('i', "interactive", "enable interactive mode even in presence of tasks",
						false, null, arg -> interactive[0] = true),
				new Option(Option.NO_SHORT_NAME, "non-interactive", "disable interactive mode even when no tasks are present",
						false, null, arg -> interactive[0] = false),
				new Option('v', "verbose", "verbose mode, same as --log=debug",
						false, null, arg -> logLevel[0] = TPLogger.DEBUG),
				new Option(Option.NO_SHORT_NAME, "root", "set the root directory of the built project",
						true, "DIR", root -> {
					Path newRoot = Paths.get(root).toAbsolutePath();
					if (Files.isDirectory(newRoot)) {
						rootDirectory[0] = newRoot;
					} else {
						if (Files.exists(newRoot)) {
							System.err.println("Can't use " + newRoot + " as root, not a directory");
						} else {
							System.err.println("Can't use " + newRoot + " as root, file does not exist");
						}
						System.exit(EXIT_CODE_ARGUMENT_ERROR);
					}
				}),
				new Option(Option.NO_SHORT_NAME, "machine-readable-output", "create machine readable output, disables implicit interactivity",
						false, null, arg -> machineReadableOutput[0] = true),
				new Option(Option.NO_SHORT_NAME, "allow-broken-build-scripts", "ignore build scripts which fail to compile and allow to run without them",
						false, null, arg -> allowBrokenBuildScripts[0] = true),
				new Option(Option.NO_SHORT_NAME, "reload-supported", "signal that launcher will handle reload requests (exit code "+EXIT_CODE_RELOAD+"), enables 'reload' command",
						false, null, arg -> reloadSupported[0] = true),
				new Option('h', "help", "show this help and exit", false, null, arg -> {
					Option.printWemiHelp(options[0]);
					System.exit(EXIT_CODE_SUCCESS);
				}),
				new Option(Option.NO_SHORT_NAME, "version", "output version information and exit", false, null, arg -> {
					System.err.println("Wemi "+WEMI_VERSION);
					System.err.println("Copyright 2017–2019 Jan Polák");
					System.err.println("<https://github.com/Darkyenus/WEMI>");
					System.exit(EXIT_CODE_SUCCESS);
				})
		};

		final List<String> taskArguments = Option.parseOptions(args, options[0]);
		if (taskArguments == null) {
			Option.printWemiHelp(options[0]);
			System.exit(EXIT_CODE_ARGUMENT_ERROR);
			return;
		}

		if (rootDirectory[0] == null) {
			rootDirectory[0] = Paths.get(".");
		}

		try {
			rootDirectory[0] = rootDirectory[0].toRealPath(LinkOption.NOFOLLOW_LINKS);
		} catch (IOException e) {
			System.err.println("Failed to resolve real path of root directory: "+rootDirectory[0]);
			e.printStackTrace(System.err);
			System.exit(EXIT_CODE_OTHER_ERROR);
		}

		final Path buildDirectory = rootDirectory[0].resolve("build");
		final Path cacheDirectory = buildDirectory.resolve("cache");

		final ArrayList<Path> paths = new ArrayList<>();
		final String fullClassPath = System.getProperty("java.class.path");
		{
			int start = 0;
			while (start < fullClassPath.length()) {
				int end = fullClassPath.indexOf(File.pathSeparatorChar, start);
				if (end == -1) {
					end = fullClassPath.length();
				}

				paths.add(Paths.get(fullClassPath.substring(start, end)));
				start = end + 1;
			}
		}

		final Object[] launchOptions = new Object[OPTIONS_SIZE];
		launchOptions[OPTION_PATH_ROOT_FOLDER] = rootDirectory[0];
		launchOptions[OPTION_PATH_BUILD_FOLDER] = buildDirectory;
		launchOptions[OPTION_PATH_CACHE_FOLDER] = cacheDirectory;
		launchOptions[OPTION_BOOL_CLEAN_BUILD] = cleanBuild[0];
		launchOptions[OPTION_BOOL_INTERACTIVE] = interactive[0];
		launchOptions[OPTION_BOOL_MACHINE_READABLE] = machineReadableOutput[0];
		launchOptions[OPTION_BOOL_ALLOW_BROKEN_BUILD_SCRIPTS] = allowBrokenBuildScripts[0];
		launchOptions[OPTION_BOOL_RELOAD_SUPPORTED] = reloadSupported[0];
		launchOptions[OPTION_LIST_OF_STRING_TASKS] = taskArguments;
		launchOptions[OPTION_LIST_OF_PATH_RUNTIME_CLASSPATH] = paths;
		launchOptions[OPTION_LOG_LEVEL] = logLevel[0];

		wemi.boot.LaunchKt.launch(launchOptions);
	}
}
