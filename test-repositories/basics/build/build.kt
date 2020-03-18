@file:BuildDependencyRepository("jitpack", "https://jitpack.io")
@file:BuildDependency("com.github.esotericsoftware:jsonbeans:0.9")

//@file:BuildDependency("com.darkyen.wemi", "wemi-plugin-jvm-hotswap", "cd036c9a8d")//TODO Experimental

import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.documentation.DokkaOptions.SourceLinkMapItem
import wemi.dependency.*
import wemi.*
import wemi.generation.*
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

    repositories add { Jitpack }

    libraryDependencies add { dependency("org.slf4j:slf4j-api:1.7.22") }
    libraryDependencies add { dependency("com.github.Darkyenus:tproll:v1.2.4") }
    libraryDependencies add { dependency("com.h2database:h2:1.4.196") }
    libraryDependencies add { kotlinDependency("reflect") }

    // -SNAPSHOT example
    repositories add { Repository("spigot-snapshots", "https://hub.spigotmc.org/nexus/content/repositories/snapshots", releases = false) }
    repositories add { sonatypeOss("snapshots") }
    libraryDependencies add { dependency("org.spigotmc", "spigot-api", "1.14.2-R0.1-SNAPSHOT") }

    compilerOptions[JavaCompilerFlags.customFlags] = { it + "-Xlint:all" }

    compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xno-optimize" } // (example, not needed)
    compilerOptions[KotlinCompilerFlags.incremental] = { true }

    extend(running) {
        projectName set { "Greeting Simulator $startYear" }
    }

    // JUnit 5
    libraryDependencies add { Dependency(JUnitAPI, scope=ScopeTest) }
    libraryDependencies add { Dependency(JUnitEngine, scope=ScopeTest) }

    // JUnit 4
    //libraryDependencies add { dependency("junit:junit:4.12") }
    //libraryDependencies add { Dependency(JUnit4Engine, scope=ScopeTest) }

    generateSources { root ->
        val version = projectVersion.get()
        println("Version: $version")

        generateJavaConstantsFile(root, "basics.Version", mapOf(
                "VERSION" to Constant.StringConstant(version, "The project version"),
                "BUILD_TIME" to Constant.LongConstant(System.currentTimeMillis()),
                "A_RATHER_COMPLICATED_STRING" to Constant.StringConstant("ěščřžýáíé\n\n\u1234\u5678  👾 \"truly complex", "This is a test of the constant system.\nIt even has newlines, /* and nested comments! */\r\nThis could prove to be complicated.")
        ))

        generateKotlinConstantsFile(root, "basics.RandomNumber", mapOf(
                "RANDOM_NUMBER" to Constant.IntConstant(4, "Chosen by a fair dice roll"),
                "A_RATHER_COMPLICATED_STRING" to Constant.StringConstant("ěščřžýáíé\n\n\u1234\u5678  👾 \"truly complex", "This is a test of the constant system.\nIt even has newlines, /* and nested comments! */\r\nThis could prove to be complicated.")
        ))
    }

    generateClasspath { root ->
        val classFile = root / "generated" / "Generated.class"
        Files.createDirectories(classFile.parent)
        /*
        Pre-compiled:
        package generated;
        public class Generated {
            public static final boolean REALLY_GENERATED = true;
        }
         */
        Files.write(classFile, byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00, 0x00, 0x00, 0x34, 0x00, 0x11, 0x0A, 0x00, 0x03, 0x00, 0x0E, 0x07, 0x00, 0x0F, 0x07, 0x00, 0x10, 0x01, 0x00, 0x10, 0x52, 0x45, 0x41, 0x4C, 0x4C, 0x59, 0x5F, 0x47, 0x45, 0x4E, 0x45, 0x52, 0x41, 0x54, 0x45, 0x44, 0x01, 0x00, 0x01, 0x5A, 0x01, 0x00, 0x0D, 0x43, 0x6F, 0x6E, 0x73, 0x74, 0x61, 0x6E, 0x74, 0x56, 0x61, 0x6C, 0x75, 0x65, 0x03, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x06, 0x3C, 0x69, 0x6E, 0x69, 0x74, 0x3E, 0x01, 0x00, 0x03, 0x28, 0x29, 0x56, 0x01, 0x00, 0x04, 0x43, 0x6F, 0x64, 0x65, 0x01, 0x00, 0x0F, 0x4C, 0x69, 0x6E, 0x65, 0x4E, 0x75, 0x6D, 0x62, 0x65, 0x72, 0x54, 0x61, 0x62, 0x6C, 0x65, 0x01, 0x00, 0x0A, 0x53, 0x6F, 0x75, 0x72, 0x63, 0x65, 0x46, 0x69, 0x6C, 0x65, 0x01, 0x00, 0x0E, 0x47, 0x65, 0x6E, 0x65, 0x72, 0x61, 0x74, 0x65, 0x64, 0x2E, 0x6A, 0x61, 0x76, 0x61, 0x0C, 0x00, 0x08, 0x00, 0x09, 0x01, 0x00, 0x13, 0x67, 0x65, 0x6E, 0x65, 0x72, 0x61, 0x74, 0x65, 0x64, 0x2F, 0x47, 0x65, 0x6E, 0x65, 0x72, 0x61, 0x74, 0x65, 0x64, 0x01, 0x00, 0x10, 0x6A, 0x61, 0x76, 0x61, 0x2F, 0x6C, 0x61, 0x6E, 0x67, 0x2F, 0x4F, 0x62, 0x6A, 0x65, 0x63, 0x74, 0x00, 0x21, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x19, 0x00, 0x04, 0x00, 0x05, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00, 0x02, 0x00, 0x07, 0x00, 0x01, 0x00, 0x01, 0x00, 0x08, 0x00, 0x09, 0x00, 0x01, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x1D, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x2A, 0xB7.toByte(), 0x00, 0x01, 0xB1.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x0B, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x02, 0x00, 0x0D))
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
            var sourceRootFileSet = sources.get()
            while (sourceRootFileSet != null) {
                options.sourceLinks.add(SourceLinkMapItem(
                        sourceRootFileSet!!.root,
                        "https://github.com/Darkyenus/WEMI/tree/master/test%20repositories/basics/${root.relativize(sourceRootFileSet!!.root).toString()}",
                        "#L"
                ))
                sourceRootFileSet = sourceRootFileSet?.next
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
 * Running this project should produce the same result as running [basics], after it was published with `basics/publish`.
 */
val basicsFromRepository by project(Archetypes.AggregateJVMProject) {
    libraryDependencies add { dependency("com.darkyen:basics:1.0-SNAPSHOT") }

    mainClass set { "basics.GreeterMainKt" }
}