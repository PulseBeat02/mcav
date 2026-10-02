# Bukkit Platform Module

```{note}
The Bukkit module supports Paper servers running Minecraft 26.3. It follows the latest version of Minecraft and does
not support older versions. Paper 26.3 has only alpha builds as of 2026-09-27; MCAV is built against build 49.
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
The Bukkit module uses server internals through paperweight-userdev, compiled against the mappings the server runs
with, so nothing is remapped; it is a library, not a plugin, so it has to be on your plugin's class path, shaded into
the plugin or downloaded when the plugin loads (the sandbox plugin downloads it with Gremlin). Its POM declares
`mcav-common`, which brings the JavaCV natives, as a compile dependency, together with Netty, Guava and Gson, which the
server already has; leave them out of a shaded jar. When you use the installer for the other modules, a configuration
looks like this:

```kotlin
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT") {
        // the installer downloads mcav-common; the server already has Netty, Guava and Gson
        exclude(group = "me.brandonli", module = "mcav-common")
        exclude(group = "io.netty")
        exclude(group = "com.google.guava")
        exclude(group = "com.google.code.gson")
    }
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

`inject` throws an `UnsupportedServerVersionException` when the server does not run Minecraft 26.3. It is an
`IllegalStateException`, so catch it to log a clear message and disable your plugin, as the sandbox plugin does.
