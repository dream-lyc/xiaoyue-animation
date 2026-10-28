plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("com.hierynomus:smbj:0.13.0"); implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("com.google.code.gson:gson:2.11.0"); testImplementation(kotlin("test-junit5")); testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4"); testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4") }
tasks.test { useJUnitPlatform() }
