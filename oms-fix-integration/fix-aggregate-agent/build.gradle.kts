dependencies {
    implementation(project(":oms-fix-integration:fix-common"))
    implementation(project(":oms-common"))
    implementation(libs.aeron.client)
    implementation(libs.agrona)
    implementation(libs.gflog.api)
    // TODO(M2): add Artio dependency when wiring FIX session
}
