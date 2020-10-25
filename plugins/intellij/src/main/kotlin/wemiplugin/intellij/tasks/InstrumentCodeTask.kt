package wemiplugin.intellij.tasks

// TODO(jp): Implement

/*
class IntelliJInstrumentCodeTask extends ConventionTask {
	private static final String FILTER_ANNOTATION_REGEXP_CLASS = 'com.intellij.ant.ClassFilterAnnotationRegexp'
	private static final LOADER_REF = "java2.loader"
	private static final ASM_REPO_URL = "https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-third-party-dependencies"
	private static final FORMS_REPO_URL = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"

	@Internal
	SourceSet sourceSet

			@Input
			@Optional
			IdeaDependency ideaDependency

			Object javac2
			Object compilerVersion

			@OutputDirectory
			File outputDir

			@InputFiles
			@SkipWhenEmpty
			FileTree getOriginalClasses() {
		def output = sourceSet.output
				return output.hasProperty("classesDirs") ?
		project.files(output.classesDirs.from).asFileTree :
		project.fileTree(output.classesDir)
	}

	@InputFile
	@Optional
	File getJavac2() {
		javac2 != null ? project.file(javac2) : null
	}

	@Deprecated
	void setJavac2(Object javac2) {
		this.javac2 = javac2
	}

	@Deprecated
	void javac2(Object javac2) {
		this.javac2 = javac2
	}

	@Input
	String getCompilerVersion() {
		Utils.stringInput(compilerVersion)
	}

	void setCompilerVersion(Object compilerVersion) {
		this.compilerVersion = compilerVersion
	}

	void compilerVersion(Object compilerVersion) {
		this.compilerVersion = compilerVersion
	}


	@InputFiles
	FileCollection getSourceDirs() {
		return project.files(sourceSet.allSource.srcDirs.findAll { !sourceSet.resources.contains(it) && it.exists() })
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	@TaskAction
	void instrumentClasses() {
		def outputDir = getOutputDir()
		copyOriginalClasses(outputDir)

		def classpath = compilerClassPath()
		ant.taskdef(name: 'instrumentIdeaExtensions',
		classpath: classpath.asPath,
		loaderref: LOADER_REF,
		classname: 'com.intellij.ant.InstrumentIdeaExtensions')

		logger.info("Compiling forms and instrumenting code with nullability preconditions")
		boolean instrumentNotNull = prepareNotNullInstrumenting(classpath)
		instrumentCode(getSourceDirs(), outputDir, instrumentNotNull)
	}

	private FileCollection compilerClassPath() {
		// local compiler
		def ideaDependency = getIdeaDependency()
		def javac2 = getJavac2()
		if (ideaDependency != null && javac2?.exists()) {
			return project.files(
					javac2,
					project.fileTree("$ideaDependency.classes/lib").include(
							'jdom.jar',
							'asm-all.jar',
							'asm-all-*.jar',
							'jgoodies-forms.jar',
							'forms-*.jar',
					)
			)
		}

		return compilerClassPathFromMaven()
	}

	private ConfigurableFileCollection compilerClassPathFromMaven() {
		def compilerVersion = getCompilerVersion()
		def dependency = project.dependencies.create("com.jetbrains.intellij.java:java-compiler-ant-tasks:$compilerVersion")
		def intellijRepoUrl = project.extensions.findByType(IntelliJPluginExtension)?.intellijRepo ?: IntelliJPlugin.DEFAULT_INTELLIJ_REPO
		def repos = [project.repositories.maven { it.url = "$intellijRepoUrl/${Utils.releaseType(compilerVersion)}" },
				project.repositories.maven { it.url = ASM_REPO_URL },
				project.repositories.maven { it.url = FORMS_REPO_URL }]
		try {
			return project.files(project.configurations.detachedConfiguration(dependency).files)
		}
		finally {
			project.repositories.removeAll(repos)
		}
	}


	private void copyOriginalClasses(@NotNull File outputDir) {
		outputDir.deleteDir()
		project.copy {
			from getOriginalClasses()
			into outputDir
		}
	}

	private boolean prepareNotNullInstrumenting(@NotNull FileCollection classpath) {
		try {
			ant.typedef(name: 'skip', classpath: classpath.asPath, loaderref: LOADER_REF,
			classname: FILTER_ANNOTATION_REGEXP_CLASS)
		} catch (BuildException e) {
			def cause = e.getCause()
			if (cause instanceof ClassNotFoundException && FILTER_ANNOTATION_REGEXP_CLASS == cause.getMessage()) {
				logger.info("Old version of Javac2 is used, " +
						"instrumenting code with nullability will be skipped. Use IDEA >14 SDK (139.*) to fix this")
				return false
			} else {
				throw e
			}
		}
		return true
	}

	private void instrumentCode(@NotNull FileCollection srcDirs, @NotNull File outputDir, boolean instrumentNotNull) {
		def headlessOldValue = System.setProperty('java.awt.headless', 'true')
		ant.instrumentIdeaExtensions(srcdir: srcDirs.asPath,
		destdir: outputDir, classpath: sourceSet.compileClasspath.asPath,
		includeantruntime: false, instrumentNotNull: instrumentNotNull) {
		if (instrumentNotNull) {
			ant.skip(pattern: 'kotlin/Metadata')
		}
	}
		if (headlessOldValue != null) {
			System.setProperty('java.awt.headless', headlessOldValue)
		} else {
			System.clearProperty('java.awt.headless')
		}
	}

}

private static void configureInstrumentation(@NotNull Project project, @NotNull IntelliJPluginExtension extension) {
    Utils.info(project, "Configuring compile tasks")
    project.sourceSets.all { SourceSet sourceSet ->
        def instrumentTask = project.tasks.create(sourceSet.getTaskName('instrument', 'code'), IntelliJInstrumentCodeTask)
        instrumentTask.sourceSet = sourceSet
        instrumentTask.with {
            dependsOn sourceSet.classesTaskName
            onlyIf { extension.instrumentCode }
            conventionMapping('compilerVersion', {
                def version = extension.version
                if (version && version.endsWith('-SNAPSHOT')) {
                    if (extension.type == 'CL') {
                        return "CLION-$version".toString()
                    }
                    if (extension.type == 'RD') {
                        return "RIDER-$version".toString()
                    }
                    if (extension.type == 'PY' || extension.type == 'PC') {
                        return "PYCHARM-$version".toString()
                    }
                    return version
                }
                return IdeVersion.createIdeVersion(ideaDependency.buildNumber).asStringWithoutProductCode()
            })
            conventionMapping('ideaDependency', { extension.ideaDependency })
            conventionMapping('javac2', {
                def javac2 = project.file("$extension.ideaDependency.classes/lib/javac2.jar")
                if (javac2?.exists()) {
                    return javac2
                }
            })
            conventionMapping('outputDir', {
                def output = sourceSet.output
                def classesDir = output.classesDirs.first()
                new File(classesDir.parentFile, "${sourceSet.name}-instrumented")
            })
        }

        // A dedicated task ensures that sources substitution is always run,
        // even when the instrumentCode task is up-to-date.
        def updateTask = project.tasks.create('post' + instrumentTask.name.capitalize())
        updateTask.with {
            dependsOn instrumentTask
            onlyIf { extension.instrumentCode }
            doLast {
                // Set the classes dir to the new one with the instrumented classes
                sourceSet.output.classesDirs.from = instrumentTask.outputDir
            }
        }

        // Ensure that our task is invoked when the source set is built
        sourceSet.compiledBy(updateTask)
    }
}

*/