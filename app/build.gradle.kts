plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose"); id("com.google.devtools.ksp") }
android {
    namespace = "cn.childtv"
    compileSdk = 35
    defaultConfig { applicationId = "cn.childtv"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "0.1.6"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/adminAssets"))
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
val bundleAdmin by tasks.registering(Sync::class) {
    from(rootProject.file("web-admin/dist")); into(layout.buildDirectory.dir("generated/adminAssets/admin"))
    doFirst { check(rootProject.file("web-admin/dist/index.html").exists()) { "Build web-admin first: cd web-admin && npm ci && npm run build" } }
}
val verifyAdmin by tasks.registering { doLast { check(rootProject.file("web-admin/dist/index.html").isFile) { "Missing desktop bundle: cd web-admin && npm ci && npm run build" } } }
bundleAdmin { dependsOn(verifyAdmin) }
tasks.named("preBuild") { dependsOn(bundleAdmin, verifyAdmin) }
dependencies {
    implementation(project(":core"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
