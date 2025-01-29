# Getting Started

## Common Platform

MCAV is a Java library that can be used in any project. To get started, you need to add the MCAV dependency to your
project. For more information about other modules, see the Minecraft or Installer documentation.

```{note}
All of MCAV's modules require at least Java 21 in order to run.
```

```kts
repositories {
    maven("https://repo.brandonli.me/snapshots")
}
```

```kts
dependencies {
    implementation("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```