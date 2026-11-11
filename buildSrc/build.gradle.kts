plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    // Makes the version catalog (`libs`) usable from the precompiled convention plugin.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
