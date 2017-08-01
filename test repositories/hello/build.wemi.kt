
///include something
///library com.esotericsoftware:jsonbeans:0.7
///resolver something@something.com

/*
val shared = scope {

}

val hello = project {

}
 */

val someTask = "Loaded".apply {
    println(this)
    println(WemiVersion)
}

/*val myProject by project {
    println("Created new project named $name at ${projectRoot.canonicalPath}!\nAlso check this:")
}*/
