# Bukkit Platform Module

```{note}
The Bukkit module only supports the latest version of Minecraft. It will never have support for any other versions.
```

The Bukkit platform module is an external module that provides Minecraft-specific playback for
players. There are several useful utilities to output video to maps, entities, scoreboards, and chat in this module. If
you would like access to the Bukkit platform module, add the `mcav-bukkit` module.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
}
```

```{note}
The Bukkit module uses NMS (net.minecraft.server) code to access the Minecraft server. It uses the paperweight-userdev
plugin to access internals, meaning that you must shade this into your plugin so it can be remapped to the correct
mappings. If using the installer, you should expect a configuration like so:

```kotlin
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT");
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT");
    compileOnly("me.brandonli:mcav-common:1.0.0-SNAPSHOT");
}
```

## Getting Started

You must pass the plugin class to the `MCAVApi` install method to initialize the Bukkit module. Then, you must inject
your plugin instance using the `inject` method, as `mcav-bukkit` requires a plugin instance.

```java
  final MCAVApi api = MCAV.api();
  api.install(BukkitModule.class);

  final BukkitModule module = api.getModule(BukkitModule.class);
  module.inject(this);
```