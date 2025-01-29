## Minecraft Platform Module

The Minecraft platform module is an external module that provides Minecraft-specific playback for
players. There are several useful utilities to output video to maps, entities, scoreboards, and chat in this module.

If you would like access to the Bukkit platform module, add the following dependency to the project instead of the
`mcav-common` dependency:

```kts
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
}
```

```{note}
The Bukkit module uses NMS (net.minecraft.server) code to access the Minecraft server. It uses the paperweight-userdev
plugin to access internals, meaning that you must shade this into your plugin so it can be remapped to the correct
mappings. If using the installer, you should expect a configuration like so:

```kts
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT");
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT");
    compileOnly("me.brandonli:mcav-common:1.0.0-SNAPSHOT");
}
```

```

## Getting Started

You must call the `initialize()` method with your own plugin, as `mcav-bukkit` requires a plugin instance.

```java
  final Plugin plugin = ...;
  MCAVBukkit.inject(plugin);
```