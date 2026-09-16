# Bukkit Platform Module

```{note}
The Bukkit module supports Paper servers running Minecraft 26.2. It follows the latest version of Minecraft and does
not support older versions.
```

The Bukkit platform module provides Minecraft-specific playback: it shows video and images on maps, blocks, text
display entities, scoreboards, and in chat, and builds and hosts resource packs for audio. Add the `mcav-bukkit`
module to use it.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
}
```

````{note}
The Bukkit module uses server internals through paperweight-userdev, so it must be shaded into your plugin to be
remapped to the mappings of the server. When you use the installer for the other modules, a configuration looks like
this:

```kotlin
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT")
    compileOnly("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```
````

## Getting Started

Pass the Bukkit module to `install`, then hand it your plugin instance with `inject`, because the module schedules
work on the server and registers listeners in the name of your plugin.

```java
  final MCAVApi api = MCAV.api();
  api.install(BukkitModule.class);

  final BukkitModule bukkitModule = api.getModule(BukkitModule.class);
  bukkitModule.inject(this);
```

`inject` throws an `UnsupportedServerVersionException` when the server does not run Minecraft 26.2. It is an
`IllegalStateException`, so catch it to log a clear message and disable your plugin, as the sandbox plugin does.
