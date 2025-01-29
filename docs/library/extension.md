# Creating an Extension

By default, most of MCAV's modules (except for the `mcav-installer` module) uses the super lightweight module system provided by [PL4J](https://github.com/pf4j/pf4j).

To start, import the module as an `api` dependency.

```java
dependencies {
    api("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```

Now, create a new module class with the `@Extension` qualifier so that it can be recognized and loaded. Now, when another user shades your library, MCAV will detect it on the classpath and automatically load it and stop it as needed!

```java
import me.brandonli.mcav.MCAVModule;
import org.pf4j.Extension;

@Extension
public final class ExampleModule implements MCAVModule {

  @Override
  public void start() {
    // no-op
  }

  @Override
  public void stop() {
    // no-op
  }

  @Override
  public String getModuleName() {
    return "example";
  }
}

```

This is useful for creating, for example, look-up tables or classes that require additional handling. Or injecting other fields, like in the `mcav-bukkit` module when we inject the `Plugin` instance.