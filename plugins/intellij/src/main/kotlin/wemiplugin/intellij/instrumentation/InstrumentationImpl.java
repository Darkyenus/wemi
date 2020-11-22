package wemiplugin.intellij.instrumentation;

import com.intellij.compiler.instrumentation.FailSafeClassReader;
import com.intellij.compiler.instrumentation.InstrumentationClassFinder;
import com.intellij.compiler.instrumentation.InstrumenterClassWriter;
import com.intellij.compiler.notNullVerification.NotNullVerifyingInstrumenter;
import com.intellij.uiDesigner.compiler.AlienFormFileException;
import com.intellij.uiDesigner.compiler.AsmCodeGenerator;
import com.intellij.uiDesigner.compiler.FormErrorInfo;
import com.intellij.uiDesigner.compiler.NestedFormLoader;
import com.intellij.uiDesigner.compiler.Utils;
import com.intellij.uiDesigner.lw.CompiledClassPropertiesProvider;
import com.intellij.uiDesigner.lw.LwRootContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.org.objectweb.asm.AnnotationVisitor;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Lazily loaded implementation. Loaded in a class loader that includes dependencies,
 * which are only provided on compilation.
 */
@SuppressWarnings("unused") // Invoked reflectively
public final class InstrumentationImpl implements Instrumentation {

	private static final Logger LOG = LoggerFactory.getLogger(InstrumentationImpl.class);

	@Override
	public boolean instrument(@NotNull Path javaHome, @NotNull List<Path> compilationClasspath,
	                          @NotNull List<Path> notNullClasses, @NotNull List<String> notNullClassSkipPatterns, @NotNull String[] notNullAnnotations,
	                          @NotNull List<Path> formFiles, @NotNull Path classRoot) {
		InstrumentationClassFinder finder = buildClasspathClassLoader(compilationClasspath, javaHome);

		boolean hasErrors = false;

		if (notNullAnnotations.length > 0) {
			int instrumented = 0;
			for (Path classFile : notNullClasses) {
				final Boolean result = instrumentNotNull(finder, classFile, notNullClassSkipPatterns, notNullAnnotations);
				if (result != null) {
					if (result) {
						instrumented++;
					} else {
						hasErrors = true;
					}
				}
			}
			LOG.info("Added @NotNull assertions to {} file(s)", instrumented);
		}

		if (!generateForms(finder, formFiles, classRoot)) {
			hasErrors = true;
		}

		return !hasErrors;
	}

	private static InstrumentationClassFinder buildClasspathClassLoader(@NotNull List<Path> compilationClasspath, @NotNull Path javaHome) {
		final ArrayList<URL> urls = new ArrayList<>();
		for (Path path : compilationClasspath) {
			try {
				urls.add(path.toUri().toURL());
			} catch (Exception e) {
				LOG.warn("Failed to turn {} into URL", path, e);
			}
		}

		try {
			urls.add(InstrumentationClassFinder.createJDKPlatformUrl(javaHome.toAbsolutePath().normalize().toString()));
		} catch (MalformedURLException e) {
			LOG.warn("Failed to turn java home {} into URL", javaHome, e);
		}

		return new InstrumentationClassFinder(urls.toArray(new URL[0])) {

			private final ClassLoader cl = getClass().getClassLoader();

			@Override
			protected InputStream lookupClassAfterClasspath(String internalClassName) {
				internalClassName += ".class";
				LOG.debug("Manually looking up class data for {}", internalClassName);
				return cl.getResourceAsStream(internalClassName);
			}
		};
	}

	private static Boolean instrumentNotNull(@NotNull InstrumentationClassFinder finder, @NotNull Path classFile, @NotNull List<String> notNullClassSkipPatterns, @NotNull String[] notNullAnnotations) {
		LOG.debug("Adding @NotNull assertions to {}", classFile);

		try (InputStream inputStream = Files.newInputStream(classFile)) {
			FailSafeClassReader reader = new FailSafeClassReader(inputStream);
			int version = InstrumenterClassWriter.getClassFileVersion(reader);
			if ((version & 0xFFFF) >= Opcodes.V1_5 && !shouldBeSkippedByAnnotationPattern(reader, notNullClassSkipPatterns)) {
				ClassWriter writer = new InstrumenterClassWriter(reader, InstrumenterClassWriter.getAsmClassWriterFlags(version), finder);
				if (NotNullVerifyingInstrumenter.processClassFile(reader, writer, notNullAnnotations)) {
					Files.write(classFile, writer.toByteArray());
					LOG.debug("@NotNull assertions successfully added to {}", classFile);
					return true;
				}
			}
			return null;
		} catch (IOException e) {
			LOG.warn("Failed to instrument @NotNull assertion for {}", classFile, e);
		} catch (Exception e) {
			LOG.warn("@NotNull instrumentation failed for {}", classFile, e);
		}
		return false;
	}

	private static boolean shouldBeSkippedByAnnotationPattern(ClassReader reader, List<String> classSkipPatterns) {
		if (classSkipPatterns.isEmpty()) {
			return false;
		}

		final boolean[] result = new boolean[]{false};
		reader.accept(new ClassVisitor(Opcodes.ASM7) {
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (!result[0] && classSkipPatterns.contains(Type.getType(desc).getClassName())) {
					result[0] = true;
				}

				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return result[0];
	}

	private static boolean generateForms(@NotNull InstrumentationClassFinder finder, @NotNull List<Path> formFiles, @NotNull Path classRoot) {
		boolean hasErrors = false;

		final HashMap<String, Path> class2form = new HashMap<>();

		for (Path formFile : formFiles) {
			LOG.debug("Compiling form {}", formFile);

			LwRootContainer rootContainer;
			try {
				rootContainer = Utils.getRootContainer(formFile.toUri().toURL(), new CompiledClassPropertiesProvider(finder.getLoader()));
			} catch (AlienFormFileException e) {
				// Bad file format, probably not a GUI builder form
				LOG.debug("Skipping {}, invalid format", formFile, e);
				continue;
			} catch (Exception e) {
				LOG.warn("Cannot process form file {}", formFile, e);
				hasErrors = true;
				continue;
			}

			final String classToBind = rootContainer.getClassToBind();
			if (classToBind == null) {
				continue;
			}
			{
				Path alreadyProcessedForm = class2form.get(classToBind);
				if (alreadyProcessedForm != null) {
					LOG.warn("{}: The form is bound to the class {}.\nAnother form {} is also bound to this class.", formFile, classToBind, alreadyProcessedForm);
					hasErrors = true;
					continue;
				}
			}

			final String name = classToBind.replace('.', '/');
			Path classFile = getClassFile(classRoot, name);
			if (classFile == null) {
				LOG.warn("{}: Class to bind does not exist: {}", formFile, classToBind);
				continue;
			}

			class2form.put(classToBind, formFile);

			try {
				final int version;
				try  (InputStream stream = Files.newInputStream(classFile)) {
					version = InstrumenterClassWriter.getClassFileVersion(new ClassReader(stream));
				}

				InstrumenterClassWriter classWriter = new InstrumenterClassWriter(InstrumenterClassWriter.getAsmClassWriterFlags(version), finder);
				AsmCodeGenerator codeGenerator = new AsmCodeGenerator(rootContainer, finder, new AntNestedFormLoader(finder.getLoader(), classRoot, formFiles), false, classWriter);
				codeGenerator.patchFile(classFile.toFile());
				LOG.debug("Form compilation done {} -> {}", formFile, classFile);

				for (FormErrorInfo warning : codeGenerator.getWarnings()) {
					LOG.warn("{}: {} ({})", formFile, warning.getErrorMessage(), warning.getComponentId());
				}

				for (FormErrorInfo error : codeGenerator.getErrors()) {
					LOG.error("{}: {} ({})", formFile, error.getErrorMessage(), error.getComponentId());
					hasErrors = true;
				}

			} catch (Exception e) {
				LOG.warn("Forms instrumentation failed for {}: {}", formFile, e);
				hasErrors = true;
			}
		}

		return !hasErrors;
	}

	@Nullable
	private static Path getClassFile(@NotNull Path root, String className) {
		final String classOrInnerName = getClassOrInnerName(root, className);
		if (classOrInnerName == null) return null;
		return root.resolve(classOrInnerName + ".class");
	}

	@Nullable
	private static String getClassOrInnerName(@NotNull Path root, String className) {
		final Path classFile = root.resolve(className + ".class");
		if (Files.exists(classFile)) return className;
		int position = className.lastIndexOf('/');
		if (position == -1) return null;
		return getClassOrInnerName(root, className.substring(0, position) + '$' + className.substring(position + 1));
	}

	private static class AntNestedFormLoader implements NestedFormLoader {

		@NotNull
		private final ClassLoader                      loader;
		@NotNull
		private final HashMap<String, LwRootContainer> formCache = new HashMap<>();
		@NotNull
		private final Path                             classRoot;
		@NotNull
		private final List<Path> formFiles;

		AntNestedFormLoader(@NotNull final ClassLoader loader, @NotNull Path classRoot, @NotNull List<Path> formFiles) {
			this.loader = loader;
			this.classRoot = classRoot;
			this.formFiles = formFiles;
		}

		@Override
		public LwRootContainer loadForm(String formFilePath) throws Exception {
			if (formCache.containsKey(formFilePath)) {
				return formCache.get(formFilePath);
			}

			String lowerFormFilePath = formFilePath.toLowerCase(Locale.ENGLISH);
			LOG.debug("Searching for form {}", lowerFormFilePath);

			for (Path formFile : formFiles) {
				LOG.debug("Comparing with {}", formFile);

				if (formFile.toAbsolutePath().normalize().toString().replace(File.separatorChar, '/').toLowerCase(Locale.ENGLISH).endsWith(lowerFormFilePath)) {
					return loadForm(formFilePath, Files.newInputStream(formFile));
				}
			}

			InputStream resourceStream = loader.getResourceAsStream(formFilePath);
			if (resourceStream != null) {
				return loadForm(formFilePath, resourceStream);
			}

			throw new Exception("Cannot find nested form file " + formFilePath);
		}

		private LwRootContainer loadForm(String formFileName, InputStream resourceStream) throws Exception {
			final LwRootContainer container = Utils.getRootContainer(resourceStream, null);
			formCache.put(formFileName, container);
			return container;
		}

		@Override
		public String getClassToBindName(LwRootContainer container) {
			final String className = container.getClassToBind();
			String result = getClassOrInnerName(classRoot, className.replace('.', '/'));
			if (result != null) return result.replace('/', '.');
			return className;
		}
	}
}
