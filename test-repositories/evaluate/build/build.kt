@file:BuildDependency("org.hamcrest:hamcrest:2.1")

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import wemi.util.*
import wemi.*

val someKey by key<String>("")
val someConfig by configuration("") {
    someKey set { "someConfig" }
}
val extendedConfig by configuration("") {}
val simpleEvaluationTest by key<Unit>("Tests simple evaluation logic")



val numberKey by key<Int>("")
val multiplying by configuration("") {
    numberKey modify { it * 3 }
}
val adding by configuration("") {
    numberKey modify { it + 3 }
}
val subtracting by configuration("") {}
val modifyEvaluationTest by key<Unit>("Tests simple modifier evaluation logic")

val evaluationTest by project {

    someKey set { "project" }
    extend(extendedConfig) {
        someKey set { "extendedConfig" }
    }
    simpleEvaluationTest set {
        assertThat(someKey.get(), equalTo("project"))
        assertThat(using(someConfig){ someKey.get() }, equalTo("someConfig"))
        assertThat(using(extendedConfig){ someKey.get() }, equalTo("extendedConfig"))
        assertThat(using(someConfig, extendedConfig){ someKey.get() }, equalTo("extendedConfig"))
        assertThat(using(extendedConfig, someConfig){ someKey.get() }, equalTo("someConfig"))
    }
    autoRun(simpleEvaluationTest)
    
    
    numberKey set { 1 }
    extend(subtracting) {
        numberKey modify { it - 3 }
    }
    modifyEvaluationTest set {
        assertThat(numberKey.get(), equalTo(1))
        assertThat(using(multiplying){ numberKey.get() }, equalTo(3))
        assertThat(using(adding){ numberKey.get() }, equalTo(4))
        assertThat(using(subtracting){ numberKey.get() }, equalTo(-2))
        assertThat(using(multiplying, adding){ numberKey.get() }, equalTo(6))
        assertThat(using(multiplying, adding, adding){ numberKey.get() }, equalTo(9))
        assertThat(using(multiplying, multiplying){ numberKey.get() }, equalTo(9))
        assertThat(using(multiplying, subtracting){ numberKey.get() }, equalTo(0))
        assertThat(using(subtracting, multiplying){ numberKey.get() }, equalTo(-6))
    }
    
}

val compileErrors by project(path("errors")) {
    extend(compilingJava) {
        sources set { (projectRoot.get() / "src").fileSet(include("**.java")) }
        compilerOptions[wemi.compile.JavaCompilerFlags.customFlags] += "-Xlint:all"
    }

    extend(compilingKotlin) {
        sources set { (projectRoot.get() / "src").fileSet(include("**.kt")) }
    }
}