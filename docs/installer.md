## Installer Module

Working with the Minecraft module results in a ton of dependencies, amounting to nearly a gigabyte. Shading all of these
dependencies in your project is not recommended, as it will result in a large JAR file that is difficult to manage. For
example, shading all of the dependencies causes Paper's remapping feature to fail with an `OutOfMemoryError` because the
artifact is too large.

The installer module automatically installs MCAV Minecraft dependencies from an artifact called `mcav-minecraft-all`, on
the repository. To add the installer module, add the following dependency to your project:

```kts
dependencies {
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT")
}
```