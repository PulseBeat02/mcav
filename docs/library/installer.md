# Installer Module

The Minecraft modules bring many dependencies, together close to a gigabyte with all native libraries. Shading all of
them into a plugin produces a jar that is hard to distribute, and Paper's remapper can run out of memory on it. The
installer module downloads the library when your plugin starts instead, so your plugin jar only contains the
installer.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT")
}
```

The installer resolves a module and all of its compile and runtime dependencies from Maven repositories, copies the
jars into a folder, and adds them to a class loader. It works with every `URLClassLoader`, which includes the plugin
class loaders of Bukkit and Paper, and with the Knot class loader of Fabric.

```java
  final File dataFolder = this.getDataFolder();
  final Path dataPath = dataFolder.toPath();
  final Path folder = dataPath.resolve("libraries");
  final MCAVInstaller installer = MCAVInstaller.injector(folder, this);
  installer.loadMCAVDependencies(Artifact.COMMON);
  installer.loadMCAVDependencies(Artifact.BUKKIT);
```

`MCAVInstaller.injector(Path, Object)` adds the jars to the class loader of the object you pass, typically your plugin
instance. Use `MCAVInstaller.injector(Path, ClassLoader)` to name the class loader directly, for example outside of a
server:

```java
  final Path folder = Path.of("dependencies");
  final ClassLoader parent = Main.class.getClassLoader();
  final URLClassLoader loader = new URLClassLoader(new URL[0], parent);
  final MCAVInstaller installer = MCAVInstaller.injector(folder, loader);
  installer.loadMCAVDependencies(Artifact.COMMON);
  final Class<?> api = Class.forName("me.brandonli.mcav.MCAV", false, loader);
```

## Artifacts

`Artifact` lists every module of the library: `COMMON`, `JDA`, `HTTP`, `BROWSER`, `VM`, `VNC`, `SVC`, `LWJGL`, and
`BUKKIT`. `COMMON` is required by every other module. `loadMCAVDependencies` downloads the module in the version
`Artifact.DEFAULT_VERSION` of the group `Artifact.GROUP_ID` (`me.brandonli`), and `getArtifactId()` returns the Maven
artifact id of a module, such as `mcav-common`.

Any other Maven artifact can be loaded with `loadDependencies`, which takes the group id, artifact id, version, and a
`JarLoader`, and returns the jars it added:

```java
  final List<Path> jars = installer.loadDependencies("org.apache.commons", "commons-text", "1.12.0", JarLoader.DEFAULT_URL_LOADER);
```

`JarLoader.DEFAULT_URL_LOADER` appends the jars to a `URLClassLoader`. `JarLoader` is a functional interface with a
single method, `loadJars(Collection<Path> jars, ClassLoader loader)`, so you can add the jars in another way and pass
your loader to `loadDependencies` or to `loadMCAVDependencies(Artifact, JarLoader)`.

## Downloads and Folder Layout

Artifacts are downloaded from Maven Central, the MCAV snapshot repository, the PaperMC repository, Google's Maven
repository, and the CodeMC releases repository. The local Maven repository of the user (`~/.m2/repository`) serves as
a cache, so artifacts already downloaded by Maven or a previous start are not downloaded again. Every repository is
checked for updates daily and uses the checksum policy `fail`: a download whose checksum is missing or does not match
the one published by the repository fails the installation instead of being used.

The jars of an installed artifact are copied to

```text
<folder>/<artifact>/<groupId>/<artifactId>/<artifactId>-<version>[-<classifier>].<extension>
```

where `<artifact>` is the artifact you asked for and the file name is the one of the local Maven repository. With the
folder `libraries`, the native jar of JavaCPP that `mcav-common` needs ends up at
`libraries/mcav-common/org.bytedeco/javacpp/javacpp-1.5.14-linux-x86_64.jar`. The group and artifact folders keep
equal artifact ids of different groups apart.

On every start, a jar that is already in the folder is compared byte for byte with its source in the local Maven
repository. An identical jar is kept, so jars a running server has already loaded are never rewritten; any other jar
is copied again, first to a `.part` file next to it and then moved into place, so a crash never leaves a half-written
jar behind. Earlier versions of the installer wrote `hash.properties` and `hashes.properties` files into the folder;
nothing reads them anymore, and they are deleted.

## Errors

Loading throws an `InstallationException` when an artifact cannot be resolved, downloaded, or copied, and a
`JarInjectorException` when the jars cannot be added to the class loader. `injector(Path, Object)` also throws a
`JarInjectorException` when the object was loaded by the bootstrap class loader, which cannot receive jars.

```{note}
Paper plugins can also declare their libraries in a `PluginLoader`, as the sandbox plugin does with Gremlin. Use
whichever mechanism fits your platform.
```
