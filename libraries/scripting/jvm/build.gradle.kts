
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":kotlin-script-runtime"))
    compile(kotlinStdlib())
    compile(project(":kotlin-scripting-common"))
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

val jar = runtimeJar()
val sourcesJar = sourcesJar()
val javadocJar = javadocJar()

dist()

ideaPlugin {
    from(jar, sourcesJar)
}

standardPublicJars()

publish()
