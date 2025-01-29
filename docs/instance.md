## Creating a MCAV Instance
To start using this library, you must first create an instance of the `MCAV` class. To do this, use the factory method
provided by the `MCAVApi` class to construct a new instance.

Once you have your `MCAVApi` instance, you **MUST** call the `install` function to install and load all required
binaries automatically. This will download the required binaries and set up the library for use.

Once you're done using the library, you **MUST** call the `release` method to release all native resources and shutdown
the library. You should only store one instance of the `MCAVApi` class throughout the life-cycle of your application or
plugin.

```java
  final MCAVApi api = MCAVApi.api();
  api.install(); // installs and loads the required binaries

  // ... use the library ...

  api.release(); // releases all native resources and shuts down the library
```

## Capabilities
MCAV utilizes several low-level libraries to provide a wide range of multimedia capabilities. Sometimes, some of these
capabilities may not be available on your platform or may require additional configuration. MCAV will try its best to
install all features it can, but some features may not be available on your platform. As far as MCAV is concerned, 
usually FFmpeg, OpenCV, and yt-dlp are supported on all platforms, but VLC may not be available always. 

You can check the capabilities of your MCAV instance by calling the `hasCapability` method on the `MCAVApi` instance.
A list of capabilities can be found in the `Capability` class. 

```java
  final MCAVApi api = ...;
  if (api.hasCapability(Capability.VLC)) {
      // VLC is available
  } else {
      // VLC is not available
  }
```

Use the capabilities to determine which features are available on your platform.