plugins {
    application
}

dependencies {
    implementation(project(":oms-fix-integration:fix-common"))
    implementation(libs.artio.core)
    implementation(libs.artio.session.codecs)
    implementation(libs.aeron.client)
    implementation(libs.aeron.driver)
    implementation(libs.aeron.archive)
    implementation(libs.agrona)
    implementation(libs.gflog.api)
    // TODO(M9): add Spring Boot initiator
}

application {
    mainClass.set("com.oms.fix.client.FixClientMain")
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED"
    )
}
