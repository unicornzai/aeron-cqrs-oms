dependencies {
    implementation(project(":oms-fix-client-gateway:fix-common"))
    implementation(project(":oms-common"))
    implementation(libs.aeron.client)
    implementation(libs.agrona)
    implementation(libs.gflog.api)
}
