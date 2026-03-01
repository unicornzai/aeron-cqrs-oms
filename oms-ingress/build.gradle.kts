dependencies {
    implementation(project(":oms-common"))
    implementation(libs.aeron.client)
    // TODO(POC): add spring-boot web for REST gateway in later milestones
}
