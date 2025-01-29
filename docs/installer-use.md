## Using the Installer

To use the installer, it's super simple. You need to make sure that your current ClassLoader is an instance of the
`URLClassLoader` class, which is the default ClassLoader for most Java applications. Then, you can use the
`MCAVInstaller` class as so.

```java
  final Path folder = ...;
final ClassLoader loader = ...;
final MCAVInstaller installer = MCAVInstaller.urlClassLoaderInjector(folder, loader);
  installer.

loadMCAVDependencies(line ->System.out.

println(line));
```

This will install the MCAV dependencies into the specified folder, and you can then use the `loader` to load the MCAV
dependencies. Pass a `Consumer<String>` to the `loadMCAVDependencies` method to receive logging output from the
installer.