package wemi.documentation.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.dokka.DokkaConfiguration;
import org.jetbrains.dokka.DokkaConfigurationImpl;
import org.jetbrains.dokka.DokkaGenerator;
import org.jetbrains.dokka.DokkaModuleDescriptionImpl;
import org.jetbrains.dokka.DokkaSourceSetID;
import org.jetbrains.dokka.DokkaSourceSetImpl;
import org.jetbrains.dokka.ExternalDocumentationLinkImpl;
import org.jetbrains.dokka.PackageOptionsImpl;
import org.jetbrains.dokka.Platform;
import org.jetbrains.dokka.SourceLinkDefinitionImpl;
import org.jetbrains.dokka.utilities.DokkaLogger;
import org.slf4j.Logger;
import org.slf4j.Marker;
import wemi.documentation.DokkaInterface;
import wemi.documentation.DokkaOptions;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * {@link DokkaInterface} implementation, DO NOT TOUCH FROM ELSEWHERE THAN {@link wemi.KeyDefaults.ArchiveDokkaInterface}!!!
 *
 * In Java, because of https://youtrack.jetbrains.com/issue/KTIJ-530
 */
public class DokkaInterfaceImpl implements DokkaInterface {

	private static URL toUrl(String url) {
		try {
			return new URL(url);
		} catch (MalformedURLException e) {
			throw new RuntimeException("Failed to create URL from '"+url+"'", e);
		}
	}

	@Override
	public void execute(
			@NotNull Collection<? extends Path> classpath, @NotNull Path outputDirectory,
			@Nullable Path packageListCache, @NotNull DokkaOptions options,
			@NotNull Logger logger, @Nullable Marker loggerMarker) {

		DokkaConfiguration config = new DokkaConfigurationImpl(
				options.getModuleName(),
				options.getModuleVersion(),
				outputDirectory.toFile(),
				packageListCache != null ? packageListCache.toFile() : null,
				options.getOffline(),
				Collections.singletonList(new DokkaSourceSetImpl(
						options.getModuleName(),
						new DokkaSourceSetID(options.getModuleName(), options.getModuleName()), // ?
						classpath.stream().map(Path::toFile).collect(Collectors.toList()),
						options.getSourceRoots().stream().map(root -> root.getDir().toFile()).collect(Collectors.toSet()),
						Collections.emptySet(),
						options.getSampleRoots().stream().map(Path::toFile).collect(Collectors.toSet()),
						options.getIncludes().stream().map(Path::toFile).collect(Collectors.toSet()),
						options.getIncludeNonPublic(),
						options.getReportNotDocumented(),
						options.getSkipEmptyPackages(),
						options.getSkipDeprecated(),
						options.getJdkVersion(),
						options.getSourceLinks().stream().map(s -> new SourceLinkDefinitionImpl(s.getDir().toAbsolutePath().toString(), toUrl(s.getUrl()), s.getUrlSuffix())).collect(Collectors.toSet()),
						options.getPerPackageOptions().stream().map(p -> new PackageOptionsImpl(p.getPrefix(), p.getIncludeNonPublic(), p.getReportNotDocumented(), p.getSkipDeprecated(), false)).collect(Collectors.toList()),
						options.getExternalDocumentationLinks().stream().map(e -> new ExternalDocumentationLinkImpl(toUrl(e.getUrl()), toUrl(e.getPackageListUrl() != null ? e.getPackageListUrl() : e.getUrl()+"/package-list"))).collect(Collectors.toSet()),
						"",
						"",
						options.getNoStdlibLink(),
						options.getNoJdkLink(),
						Collections.emptySet(),
						Platform.jvm // For now
				)),
				Collections.emptyList(),// TODO(jp): The plugins are actually vital
				Collections.emptyList(),
				Collections.singletonList(new DokkaModuleDescriptionImpl(options.getModuleName(), outputDirectory.toFile(), options.getSourceRoots().stream().map(root -> root.getDir().toFile()).collect(Collectors.toSet()))),
				false
		);

		final DokkaGenerator gen = new DokkaGenerator(config, new DokkaLogger() {

			private int warningsCount = 0;
			private int errorsCount = 0;

			@Override
			public int getWarningsCount() {
				return warningsCount;
			}

			@Override
			public void setWarningsCount(int i) {
				warningsCount = i;
			}

			@Override
			public int getErrorsCount() {
				return errorsCount;
			}

			@Override
			public void setErrorsCount(int i) {
				errorsCount = i;
			}

			@Override
			public void debug(@NotNull String s) {
				logger.debug(loggerMarker, "{}", s);
			}

			@Override
			public void info(@NotNull String s) {
				logger.info(loggerMarker, "{}", s);
			}

			@Override
			public void progress(@NotNull String s) {
				logger.debug(loggerMarker, "Progress: {}", s);
			}

			@Override
			public void warn(@NotNull String s) {
				warningsCount++;
				logger.warn(loggerMarker, "{}", s);
			}

			@Override
			public void error(@NotNull String s) {
				errorsCount++;
				logger.error(loggerMarker, "{}", s);
			}
		});

		gen.generate();
	}
}
