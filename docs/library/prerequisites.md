# Library Prerequisites

MCAV is a Java library that can be used in any project. To get started, you need to add the MCAV core dependency to your
project. You must use a build system that supports Maven repositories, such as Gradle or Maven.

QEMU is not installed by default. MCAV can run out of the box for all Windows, MacOS, and all Debian/Debian-based
Linux distributions that are x86_64 or aarch64. Otherwise, you have to install some libraries manually.

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

## Installation

MCAV can run out of the box for all Windows and MacOS, but for Debian/Debian-based Linux distributions, you need to supply
an additional command line argument when running the server. Please add the folowing argument to your server's startup script:

```bash
-Djava.library.path=$HOME/.apt/usr/lib/*:${java.library.path}
```

Why? Because MCAV uses a script to automatically install libraries on Linux using `apt`, but without severe JVM hacking,
you can't supply these native libraries at runtime. So we need to add a search path for the JVM to find these custom
installed libraries.

If you are using a server hosting provider, you may need to ask them to add this argument for you.