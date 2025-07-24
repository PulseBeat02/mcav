# SVC Module

MCAV provides a module to supply audio using [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). 
To get started, you have to import the `mcav-svc` module into your project like so.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-svc:1.0.0-SNAPSHOT")
}
```

```{warning}
Unlike the other modules, you must shade this module if you are using Bukkit due to ClassLoader issues. The library
classes need to have access to the SVC plugin's classes.
```


The SVC module provides a class called the `SVCFilter`, which is used to send audio to the Simple Voice Chat plugin.

```java
  final UUID[] players = ...;
  final SVCFilter svc = SVCFilter.svc(players);
  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(svc);
  svc.start();

  final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);
  
  multiplexer.start(...);
  
  // play some media...

  svc.release();
  multiplexer.release();
```