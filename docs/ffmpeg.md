## FFmpeg
MCAV allows users to run [FFmpeg](https://ffmpeg.org/) commands directly the API. You can get the FFmpeg binary by using the
`FFmpegExecutableProvider#getFFmpegPath()` method, which returns the path to the FFmpeg binary. You can then use it to
run FFmpeg commands using the `ProcessBuilder` class in Java.

Some FFmpeg commands are provided by MCAV in the `FFmpegTemplates` class, which are useful for basic, trivial tasks such
as extracting audio from a video, or converting a video to a different format.

```java
final FFmpegCommand command = FFmpegTemplates.extractAudio("video.mp4", "vorbis", "audio.ogg");
command.execute();
```