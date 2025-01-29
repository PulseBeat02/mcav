## Using the Installer

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