dependencies {
    implementation(project(":oms-common"))
    implementation(libs.aeron.client)
    // TODO(POC): add aeron-archive for startup replay in Milestone 4
}
