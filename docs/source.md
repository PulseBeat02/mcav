## Introduction to Sources
In MCAV, a source is a component that provides some sort of starting point for a video or audio player. Sources can be
in the form of local files, URLs, streams, or device inputs. There are several ways to define a source, and different
video players may require different types of sources.

The types of sources included are `BrowserSource`, `FileSource`, `FrameSource`, `DeviceSource`, and `UriSource`. To 
create them is very simple—you just use the respective factory method within the interface.

```java
  // creating a UriSource from a URI
  final UriSource source = UriSource.uri(URI.create("https://google.com"));
```

Now, you're able to pass this source into any video player.