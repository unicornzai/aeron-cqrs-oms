dependencies {
    implementation(project(":oms-common"))
    implementation(project(":oms-fix-client-gateway:fix-common"))
    implementation(libs.aeron.client)
}
