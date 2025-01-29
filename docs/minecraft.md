## Minecraft Platform Module

The Minecraft platform module is an external module that uses PacketEvents to provide a Minecraft-specific playback for
players. There are several useful utilities to output video to maps, entities, scoreboards, and chat in this module.

If you would like access to the Bukkit platform module, add the following dependency to the project instead of the
`mcav-common` dependency:

```kts
dependencies {
    implementation("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
}
```

```{note}
The `mcav-bukkit` module already includes the `mcav-common` module, so you do not need to add it separately.
```

## Getting Started

You must call the `initialize()` method with your own plugin, as `mcav-bukkit`
uses [PacketEvents](https://github.com/retrooper/packetevents)
which requires a plugin instance.

```java
  final Plugin plugin = ...;
        MCAVBukkit.

inject(plugin);
```