## Minecraft Platform Module

The Minecraft platform module is an external module that uses PacketEvents to provide a Minecraft-specific playback for
players. There are several useful utilities to output video to maps, entities, scoreboards, and chat in this module.

If you would like access to the Minecraft platform module, add the following dependency to the project instead of the
`mcav-common` dependency:

```kts
dependencies {
    implementation("me.brandonli:mcav-minecraft:1.0.0-SNAPSHOT")
}
```

The `mcav-minecraft` module is compatible with Bukkit, Velocity, BungeeCord, Sponge, and Fabric platforms. It already
includes the `mcav-common` module, so you do not need to add it separately.