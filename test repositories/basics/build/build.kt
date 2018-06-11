@file:BuildDependencyRepository("jitpack", "https://jitpack.io")
@file:BuildDependency("com.github.esotericsoftware:jsonbeans:0.9")

@file:BuildDependency("com.darkyen.wemi", "wemi-plugin-jvm-hotswap", "cd036c9a8d")//TODO Experimental

import wemi.Configurations.compilingKotlin
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.documentation.DokkaOptions.SourceLinkMapItem
import com.esotericsoftware.jsonbeans.JsonValue

val basics by project {

    projectGroup set { "com.darkyen" }
    projectName set { "basics" }
    projectVersion set { "1.0-SNAPSHOT" }

    val startYear = "2017"

    publishMetadata modify { metadata ->
        metadata.apply {
            child("inceptionYear", startYear)
        }
    }

    repositories add { repository("jitpack", "https://jitpack.io") }

    libraryDependencies add { dependency("org.slf4j:slf4j-api:1.7.22") }
    libraryDependencies add { dependency("com.github.Darkyenus:tproll:v1.2.4") }
    libraryDependencies add { dependency("com.h2database:h2:1.4.196") }
    libraryDependencies add { kotlinDependency("reflect") }

    extend(compilingJava) {
        compilerOptions[JavaCompilerFlags.customFlags] += "-Xlint:all"
    }

    extend(compilingKotlin) {
        compilerOptions[KotlinCompilerFlags.customFlags] += "-Xno-optimize" // (example, not needed)
        compilerOptions[KotlinCompilerFlags.incremental] = true
    }

    extend(running) {
        projectName set { "Greeting Simulator $startYear" }
    }

    extend(testing) {
        // JUnit 5
        libraryDependencies add { JUnitAPI }
        libraryDependencies add { JUnitEngine }

        // JUnit 4
        //libraryDependencies add { dependency("junit:junit:4.12") }
        //libraryDependencies add { JUnit4Engine }
    }

    mainClass set { "basics.GreeterMainKt" }

    publishMetadata modify { metadata ->
        metadata.child("name").text = "Wemi Basics Example"
        metadata.child("description").text = "Demonstrates usage of Wemi"
        metadata.removeChild("url")
        metadata.removeChild("licenses")

        metadata
    }

    extend (archivingDocs) {
        archiveDokkaOptions modify { options ->
            options.outputFormat = wemi.documentation.DokkaOptions.FORMAT_HTML

            val root = projectRoot.get()
            for (sourceRoot in sourceRoots.get()) {
                options.sourceLinks.add(SourceLinkMapItem(
                        sourceRoot,
                        "https://github.com/Darkyenus/WEMI/tree/master/test%20repositories/basics/${root.relativize(sourceRoot).toString()}",
                        "#L"
                ))
            }

            options
        }
    }

    // Test of build-script dependencies
    if (JsonValue(true).toString() != "true") {
        // Does not happen.
        println("Json doesn't work!")
    }

    // Fox example
    foxColor set { "Red" }

    queryingExample set {
        val testingSourceRoots = using (testing) { sourceRoots.get() }
        val normalSourceRoots = sourceRoots.get()

        StringBuilder()
    } // Fresh string builder at the root
}

val foxColor by key<String>("Color of an animal")

val arctic by configuration("When in snowy regions") {
    foxColor set {"White"}
}

val wonderland by configuration("When in wonderland") {
    foxColor set {"Rainbow"}

    extend (arctic) {
        foxColor set {"Transparent"}
    }
}

val heaven by configuration("Like wonderland, but better", wonderland) {
    foxColor set { "Octarine" }
}


//region Full Querying order example
val queryingExample by key<StringBuilder>("String builder that is used to demonstrate querying order, see below")

val base:Configuration by configuration("") {
    queryingExample modify { it.apply { append('0') } }

    extend(foo) {
        queryingExample modify { it.apply { append('2') } }

        extend(bar) {
            queryingExample modify { it.apply { append('6') } }
        }
    }
}

val foo:Configuration by configuration("") {
    queryingExample modify { it.apply { append('1') } }

    extend(bar) {
        queryingExample modify { it.apply { append('4') } }

        extend(base) {
            queryingExample modify { it.apply { append('5') } }
        }
    }
}

val bar:Configuration by configuration("") {
    queryingExample modify { it.apply { append('3') } }

    extend(base) {
        queryingExample modify { it.apply { append('7') } }

        extend(foo) {
            queryingExample modify { it.apply { append('8') } }
        }
    }

    extend(foo) {
        queryingExample modify { it.apply { append('9') } }
    }
}

//endregion

/**
 * Running this project should produce the same result as running [hello], after it was published.
 */
val helloFromRepository by project(wemi.Archetypes.BlankJVMProject) {
    libraryDependencies add { dependency("com.darkyen:basics:1.0-SNAPSHOT") }

    mainClass set { "hello.HelloWemiKt" }
}