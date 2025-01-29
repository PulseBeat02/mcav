# Getting Started
## Common Platform
MCAV is a Java library that can be used in any project. To get started, you need to add the MCAV dependency to your
project.

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

---

## Minecraft Platform Module
If you would like access to the Minecraft platform module, add the following dependency to the project instead of the
`mcav-common` dependency:

```kts
dependencies {
    implementation("me.brandonli:mcav-minecraft:1.0.0-SNAPSHOT")
}
```

The `mcav-minecraft` module is compatible with Bukkit, Velocity, BungeeCord, Sponge, and Fabric platforms. It already
includes the `mcav-common` module, so you do not need to add it separately.