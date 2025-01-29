# Installer Module

Working with the Minecraft module results in a ton of dependencies, amounting to nearly a gigabyte. Shading all of these
dependencies in your project is not recommended, as it will result in a large JAR file that is difficult to manage. For
example, shading all of the dependencies causes Paper's remapping feature to fail with an `OutOfMemoryError` because the
artifact is too large. To start, add the following dependency to your `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT")
}
```

To use the installer, it's super simple. You need to make sure that your current ClassLoader is an instance of the
`URLClassLoader` class, which is the default ClassLoader for most Java applications. Then, you can use the
`MCAVInstaller` class as so.

```java
  final Path downloaded = Path.of("dependencies");
  final Class<InstallationExample> clazz = InstallationExample.class;
  final ClassLoader classLoader = requireNonNull(clazz.getClassLoader());
  final MCAVInstaller installer = MCAVInstaller.injector(downloaded, classLoader);
  installer.loadMCAVDependencies(Artifact.COMMON);
```

This will install the MCAV dependencies into the specified folder, and you can then use the `loader` to load the MCAV
dependencies. Pass a `Consumer<String>` to the `loadMCAVDependencies` method to receive logging output from the
installer. You can choose whether to install the `mcav-common` module, or the `mcav-bukkit` module (which contains
the `mcav-common` module already).