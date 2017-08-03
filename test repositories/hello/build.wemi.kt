
///include something
///library com.esotericsoftware:jsonbeans:0.7
///resolver something@something.com

val hello by project {

    projectGroup set {"com.darkyen"}
    projectName set {"hello"}
    projectVersion set {"1.0-SNAPSHOT"}

    startYear set {2017}

    libraryDependencies += { dependency("com.h2database:h2:1.4.196") }

    mainClass set { "HelloWemiKt" }

}
