plugins { id("com.android.application") }
android {
  namespace = "com.dsharnessmobile.adbpatchtest"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.dsharnessmobile.adbpatchtest"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "test-only"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources { noCompress += "zip" }
}
dependencies {
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
}
