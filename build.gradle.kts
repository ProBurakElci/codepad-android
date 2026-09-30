// AGP 9 has Kotlin support built in, so there is no Kotlin plugin here on
// purpose - adding one back makes the build fail outright.
plugins {
    id("com.android.application") version "9.4.1" apply false
}
