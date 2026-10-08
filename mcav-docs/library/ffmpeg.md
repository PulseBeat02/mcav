# Using FFmpeg

Besides the FFmpeg libraries the players use, MCAV bundles the FFmpeg command-line program, so you can run FFmpeg
commands without installing anything. `FFmpegExecutableProvider.getFFmpegPath()` returns the path of the program; it
is extracted on first use.

`FFmpegTemplates` contains ready-made commands for common jobs, such as extracting audio, cutting clips, creating
thumbnails, and converting containers. `FFmpegCommand.builder()` builds any other command.

```java
  final FFmpegCommand command = FFmpegTemplates.extractOggVorbis("video.mp4", "audio.ogg");
  final CommandTask task = command.createTask();
  task.runChecked(); // waits for FFmpeg and throws if it fails
```

```java
  final FFmpegCommand.Builder builder = FFmpegCommand.builder();
  builder.addInput("video.mp4");
  builder.addResolution(1280, 720);
  builder.addVideoCodec("libx264");
  builder.addOverwrite();
  builder.addOutput("small.mp4");
  final FFmpegCommand command = builder.build();
  final CommandTask task = command.createTask();
  final int exitCode = task.run();
  final String errors = task.getErrorOutput();
```

`createTask()` only creates the `CommandTask`; nothing runs until you call one of its methods, and a task can run only
once. `run()` returns the exit code, while `runChecked()` throws a `ProcessException` (an unchecked exception in
`me.brandonli.mcav.utils.runtime`) with the error output of FFmpeg when it exits with a non-zero code. Both throw an
`IOException` when FFmpeg cannot be started. `run(Duration)` and `runChecked(Duration)` kill FFmpeg when it takes
longer than the timeout. Every method of `FFmpegCommand.Builder` returns the builder.

```{note}
Always add `addOverwrite()` when the output may exist already; otherwise FFmpeg waits for a confirmation on the
console that never comes.
```
