# Creating an Extension

By default, most of MCAV's modules (except for the `mcav-installer` module) uses the super lightweight module system provided by [PL4J](https://github.com/pf4j/pf4j).

To start, import the module as an `api` dependency.

```java
dependencies {
    api("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```

Now, create your module by implementing the `MCAVModule` interface. To use your new module, pass `ExampleModule.class`
when you call the `install` method on the `MCAVApi` class.

```java
import me.brandonli.mcav.module.MCAVModule;

public final class ExampleModule implements MCAVModule {

  public ExampleModule() {
    // no-op
  }

  @Override
  public void start() {
    // startup logic here
  }

  @Override
  public void stop() {
    // shutdown logic here
  }

  @Override
  public String getModuleName() {
    return "example";
  }
}

```


This is useful for creating, for example, look-up tables or classes that require additional handling. Or injecting other fields, like in the `mcav-bukkit` module when we inject the `Plugin` instance.