# Library Prerequisites

MCAV is a Java library that can be used in any project. To get started, you need to add the MCAV core dependency to your
project. You must use a build system that supports Maven repositories, such as Gradle or Maven.

```{note}
All of MCAV's modules require at least Java 21 in order to work.
```

```kotlin
repositories {
    maven("https://repo.brandonli.me/snapshots")
}
```

```kotlin
dependencies {
    implementation("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```