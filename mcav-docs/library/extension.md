# Creating an Extension

Every feature module of MCAV, such as `mcav-bukkit` or `mcav-http`, plugs into the library through a small module
system: a module is a class that implements `MCAVModule`, which the library creates and starts during `install` and
stops during `release`. You can write your own modules the same way.

Add the core library as an `api` dependency of your module:

```kotlin
dependencies {
    api("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```

Then implement `MCAVModule`. The class needs a no-argument constructor, which the library calls.

```java
import me.brandonli.mcav.module.MCAVModule;

public final class ExampleModule implements MCAVModule {

  public ExampleModule() {
    // called by the library during install
  }

  @Override
  public void start() {
    // prepare lookup tables, start services, ...
  }

  @Override
  public void stop() {
    // release everything start() created
  }

  @Override
  public String getModuleName() {
    return "example";
  }
}
```

Pass the class to `install` and look the instance up with `getModule`:

```java
  final MCAVApi api = MCAV.api();
  api.install(ExampleModule.class);
  final ExampleModule exampleModule = api.getModule(ExampleModule.class);
```

Modules are started in the order they are passed to `install` and stopped in reverse order. They are a good place for
expensive shared state, such as lookup tables, and for objects the rest of your code needs, such as the plugin
instance the Bukkit module receives through `inject`.
