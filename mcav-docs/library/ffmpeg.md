# Using FFmpeg

Besides the FFmpeg libraries the players use, MCAV bundles the FFmpeg command-line program, so you can run FFmpeg
commands without installing anything. `FfmpegExecutableProvider.getFfmpegPath()` returns the path of the program; it
is extracted on first use.

On Linux, every `CommandTask` of an `FfmpegCommand` runs the program with `LD_LIBRARY_PATH` set to its own folder, where
the FFmpeg libraries beside it find the others they load, such as `libva`, and then to the folders of the sound and
display libraries that MCAV installs first on a system without them, as
[prerequisites](prerequisites.md#supported-platforms) describes. To start the program yourself, add
`FfmpegCommand#getEnvironment()` to the environment of the process.

`FfmpegTemplates` contains ready-made commands for common jobs, such as extracting audio, cutting clips, creating
thumbnails, and converting containers. `FfmpegCommand.builder()` builds any other command.

```java
  final FfmpegCommand command = FfmpegTemplates.extractOggVorbis("video.mp4", "audio.ogg");
  final CommandTask task = command.createTask();
  task.runChecked(); // waits for FFmpeg and throws if it fails
```

```java
  final FfmpegCommandBuilder builder = FfmpegCommand.builder();
  builder.addInput("video.mp4");
  builder.addResolution(1280, 720);
  builder.addVideoCodec("libx264");
  builder.addOverwrite();
  builder.addOutput("small.mp4");
  final FfmpegCommand command = builder.build();
  final CommandTask task = command.createTask();
  final int exitCode = task.run();
  final String errors = task.getErrorOutput();
```

`createTask()` only creates the `CommandTask`; nothing runs until you call one of its methods, and a task can run only
once. `run()` returns the exit code, while `runChecked()` throws a `ProcessException` (an unchecked `MCAVException` in
`me.brandonli.mcav.utils.runtime`) with the error output of FFmpeg when it exits with a non-zero code. Both throw a
`ProcessException` as well when FFmpeg cannot be started or the wait is interrupted. `run(Duration)` and
`runChecked(Duration)` kill FFmpeg when it takes longer than the timeout. Every method of `FfmpegCommandBuilder`
returns the builder.

```{note}
Always add `addOverwrite()` when the output may exist already; otherwise FFmpeg waits for a confirmation on the
console that never comes.
```
