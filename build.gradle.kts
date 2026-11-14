plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "de.knirpslarsi"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories { mavenCentral() }

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.swing")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("org.mozilla:rhino:1.7.15")
    // MP3 dekodieren für die Show-Zeitleiste (LGPL)
    implementation("javazoom:jlayer:1.0.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("de.knirpslarsi.ledstudio.Main") }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
tasks.withType<JavaExec> { jvmArgs("-Dfile.encoding=UTF-8") }
tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

// Schreibt die Version in die App, damit der Updater sie vergleichen kann.
tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("ledstudio/version.properties") { expand("version" to v) }
}

val stageDir = layout.buildDirectory.dir("package/input")

val stageApp by tasks.registering(Sync::class) {
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(stageDir)
}

// Eigenstaendiger Programmordner mit LEDStudio.exe und eigener Java-Laufzeit.
val appImage by tasks.registering(Exec::class) {
    dependsOn(stageApp)
    val out = layout.buildDirectory.dir("package/image")
    doFirst { delete(out) }
    val launcher = javaToolchains.launcherFor(java.toolchain).get()
    commandLine(
        launcher.metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath,
        "--type", "app-image",
        "--name", "LEDStudio",
        "--app-version", project.version.toString().substringBefore('-'),
        "--vendor", "Knirpslarsi",
        "--description", "LED Studio - RGB-Editor und Effekte fuer OpenRGB",
        "--icon", "packaging/ledstudio.ico",
        "--input", stageDir.get().asFile.absolutePath,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "de.knirpslarsi.ledstudio.Main",
        "--add-modules", "java.base,java.desktop,java.logging,java.net.http,java.naming,java.xml,java.sql,java.scripting,jdk.crypto.ec,jdk.unsupported,jdk.zipfs",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Xmx512m",
        "--dest", out.get().asFile.absolutePath,
    )
}

// Setup-EXE (Inno Setup). Installiert pro Benutzer, ohne Adminrechte.
val setupExe by tasks.registering(Exec::class) {
    dependsOn(appImage)
    val iscc = providers.environmentVariable("ISCC").orNull
        ?: listOf(
            "C:/Program Files (x86)/Inno Setup 6/ISCC.exe",
            System.getenv("LOCALAPPDATA") + "/Programs/Inno Setup 6/ISCC.exe",
        ).firstOrNull { file(it).exists() } ?: "ISCC"
    commandLine(
        iscc, "/Qp",
        "/DAppVersion=${project.version}",
        "/DSourceDir=${layout.buildDirectory.dir("package/image/LEDStudio").get().asFile.absolutePath}",
        "/DOutputDir=${layout.buildDirectory.dir("release").get().asFile.absolutePath}",
        "packaging/ledstudio.iss",
    )
}
