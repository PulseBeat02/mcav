/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.sandbox.locale;

import static me.brandonli.mcav.sandbox.locale.LocaleTools.direct;

/**
 * Every chat message the commands of the plugin send, each bound to its key in the message file.
 *
 * <p>Server owners change the wording and colors in {@code locale/mcav_<language>.properties} in the data folder of
 * the plugin; the keys listed here must stay. Call {@code build()} on a constant to render its message in the
 * configured language right before sending it.
 */
public interface Message extends LocaleTools {
  /**
   * Key {@code mcav.command.hologram.disable}: confirms {@code /mcav video hologram disable}, after which videos
   * show no hologram.
   */
  NullComponent HOLOGRAM_DISABLED = direct("mcav.command.hologram.disable");

  /**
   * Key {@code mcav.command.hologram.set}: confirms {@code /mcav video hologram set}, after which videos show their
   * title and progress in a hologram at that location.
   */
  NullComponent HOLOGRAM_LOCATION_SET = direct("mcav.command.hologram.set");

  /**
   * Key {@code mcav.qemu.error}: {@code /mcav vm create} was run, but QEMU was not found when the plugin started.
   */
  NullComponent QEMU_NOT_INSTALLED = direct("mcav.qemu.error");

  /**
   * Key {@code mcav.command.mcv2.pack}: sent to the viewers of an MCV2 screen right before their client is asked to
   * load the screen's resource pack.
   */
  NullComponent MCV2_PACK = direct("mcav.command.mcv2.pack");

  /**
   * Key {@code mcav.command.mcv2.refused}: a viewer's client declined the MCV2 pack or could not load it, so the
   * viewer sees the dithered maps.
   */
  NullComponent MCV2_REFUSED = direct("mcav.command.mcv2.refused");

  /**
   * Key {@code mcav.command.mcv2.screen.error}: no item frame holds the map id given to an MCV2 command, whose
   * argument is inserted.
   */
  UniComponent<Integer> MCV2_SCREEN_ERROR = direct("mcav.command.mcv2.screen.error", null);

  /**
   * Key {@code mcav.command.mcv2.file.error}: the file given to {@code /mcav mcv2 play} is not an MCV2 stream; the
   * reason is inserted.
   */
  UniComponent<String> MCV2_FILE_ERROR = direct("mcav.command.mcv2.file.error", null);

  /**
   * Key {@code mcav.command.mcv2.pacing}: an MCV2 screen stepped down or back up the frame rates its encoder budget
   * sustains; what it chose and why is inserted.
   */
  UniComponent<String> MCV2_PACING = direct("mcav.command.mcv2.pacing", null);

  /**
   * Key {@code mcav.command.mcv2.encode.start}: {@code /mcav mcv2 encode} started; what it encodes, how and on how many
   * threads is inserted.
   */
  UniComponent<String> MCV2_ENCODE_START = direct("mcav.command.mcv2.encode.start", null);

  /**
   * Key {@code mcav.command.mcv2.encode.progress}: how far a file encode got; the frames and the time per frame are
   * inserted.
   */
  UniComponent<String> MCV2_ENCODE_PROGRESS = direct("mcav.command.mcv2.encode.progress", null);

  /**
   * Key {@code mcav.command.mcv2.encode.done}: a file encode finished; what it made and how long it took is inserted.
   */
  UniComponent<String> MCV2_ENCODE_DONE = direct("mcav.command.mcv2.encode.done", null);

  /**
   * Key {@code mcav.command.mcv2.encode.error}: a file encode failed; the reason is inserted.
   */
  UniComponent<String> MCV2_ENCODE_ERROR = direct("mcav.command.mcv2.encode.error", null);

  /**
   * Key {@code mcav.command.mcv2.encode.busy}: {@code /mcav mcv2 encode} was run while another encode runs.
   */
  NullComponent MCV2_ENCODE_BUSY = direct("mcav.command.mcv2.encode.busy");

  /**
   * Key {@code mcav.command.mcv2.encode.cancelled}: a file encode stopped before it finished.
   */
  NullComponent MCV2_ENCODE_CANCELLED = direct("mcav.command.mcv2.encode.cancelled");

  /**
   * Key {@code mcav.command.mcv2.encode.none}: {@code /mcav mcv2 cancel} found no encode running.
   */
  NullComponent MCV2_ENCODE_NONE = direct("mcav.command.mcv2.encode.none");

  /**
   * Key {@code mcav.command.mcv2.play}: confirms that {@code /mcav mcv2 play} started the stream.
   */
  NullComponent MCV2_PLAY = direct("mcav.command.mcv2.play");

  /**
   * Key {@code mcav.command.mcv2.stop}: confirms that {@code /mcav mcv2 stop} stopped the stream.
   */
  NullComponent MCV2_STOP = direct("mcav.command.mcv2.stop");

  /**
   * Key {@code mcav.command.screen.build}: confirms that {@code /mcav screen} built a wall of maps.
   */
  NullComponent SCREEN_BUILD = direct("mcav.command.screen.build");

  /**
   * Key {@code mcav.command.image.release}: {@code /mcav image release} has removed the image.
   */
  NullComponent RELEASE_IMAGE = direct("mcav.command.image.release");

  /**
   * Key {@code mcav.command.image.release.start}: sent as soon as {@code /mcav image release} is run, before the
   * image is removed.
   */
  NullComponent RELEASE_IMAGE_START = direct("mcav.command.image.release.start");

  /**
   * Key {@code mcav.command.image.load}: an {@code /mcav image} command has finished loading and now shows the image.
   */
  NullComponent LOAD_IMAGE = direct("mcav.command.image.load");

  /**
   * Key {@code mcav.command.image.load.start}: an {@code /mcav image} command accepted its arguments and started
   * loading the image in the background.
   */
  NullComponent LOAD_IMAGE_START = direct("mcav.command.image.load.start");

  /**
   * Key {@code mcav.command.vm.path.error}: the virtual machine could not start because the QEMU program for the
   * chosen architecture, such as {@code qemu-system-x86_64}, is not on the {@code PATH} of the server.
   */
  NullComponent VM_PATH = direct("mcav.command.vm.path.error");

  /**
   * Key {@code mcav.command.interaction.remove}: {@code /mcav browser interact} or {@code /mcav vm interact} switched
   * chat input off, so the chat messages of the player go to chat again.
   */
  NullComponent INTERACT_DISABLE = direct("mcav.command.interaction.remove");

  /**
   * Key {@code mcav.command.interaction.add}: {@code /mcav browser interact} or {@code /mcav vm interact} switched
   * chat input on, so the chat messages of the player are typed into the screen.
   */
  NullComponent INTERACT_ENABLE = direct("mcav.command.interaction.add");

  /**
   * Key {@code mcav.command.vm.loading}: {@code /mcav vm create} accepted its arguments and is starting the virtual
   * machine in the background.
   */
  NullComponent VM_LOADING = direct("mcav.command.vm.loading");

  /**
   * Key {@code mcav.command.vm.create}: the virtual machine started and now shows on the maps.
   */
  NullComponent VM_CREATE = direct("mcav.command.vm.create");

  /**
   * Key {@code mcav.command.vm.release}: {@code /mcav vm release} stopped the virtual machine.
   */
  NullComponent VM_RELEASE = direct("mcav.command.vm.release");

  /**
   * Key {@code mcav.command.audio.http}: sent to every viewer of a video played with the {@code HTTP_SERVER} audio
   * output. The argument is the address of the audio web page, inserted at {@code $URL$}.
   */
  UniComponent<String> AUDIO_HTTP = direct("mcav.command.audio.http", null);

  /**
   * Key {@code mcav.command.audio.discord}: sent to every viewer of a video played with the {@code DISCORD_BOT} audio
   * output. The argument is the link to the voice channel, inserted at {@code $URL$}.
   */
  UniComponent<String> AUDIO_DISCORD = direct("mcav.command.audio.discord", null);

  /**
   * Key {@code mcav.command.video.resume}: confirms {@code /mcav video resume}.
   */
  NullComponent RESUME_PLAYER = direct("mcav.command.video.resume");

  /**
   * Key {@code mcav.command.video.resume.failed}: {@code /mcav video resume} was run, but the video player could not
   * be resumed because playback has already ended; the start command plays it again.
   */
  NullComponent RESUME_PLAYER_FAILED = direct("mcav.command.video.resume.failed");

  /**
   * Key {@code mcav.command.audio.unsupported}: a video command chose the Discord bot or the HTTP server as the audio
   * output, but that output is not enabled in {@code config.yml}.
   */
  NullComponent UNSUPPORTED_AUDIO = direct("mcav.command.audio.unsupported");

  /**
   * Key {@code mcav.command.player.unsupported}: a video command chose the {@code VLC} player, but VLC is not
   * installed on the server.
   */
  NullComponent UNSUPPORTED_PLAYER = direct("mcav.command.player.unsupported");

  /**
   * Key {@code mcav.command.player.preparing}: a video command chose the {@code VLC} player while the library is still
   * preparing VLC in the background, which takes a few minutes on the first start of a server without VLC.
   */
  NullComponent VLC_PREPARING = direct("mcav.command.player.preparing");

  /**
   * Key {@code mcav.command.ytdlp.preparing}: a video command was given a web page, such as a YouTube video, while the
   * library is still preparing yt-dlp in the background, which resolves such pages.
   */
  NullComponent YTDLP_PREPARING = direct("mcav.command.ytdlp.preparing");

  /**
   * Key {@code mcav.command.video.release.start}: sent as soon as {@code /mcav video release} is run, before the video
   * is stopped.
   */
  NullComponent RELEASE_PLAYER_START = direct("mcav.command.video.release.start");

  /**
   * Key {@code mcav.command.video.load.error}: a video command was run while another video is still starting; only
   * one video can start at a time.
   */
  NullComponent PLAYER_ERROR = direct("mcav.command.video.load.error");

  /**
   * Key {@code mcav.command.video.release}: {@code /mcav video release} has stopped the video.
   */
  NullComponent RELEASE_PLAYER = direct("mcav.command.video.release");

  /**
   * Key {@code mcav.command.video.pause}: confirms {@code /mcav video pause}.
   */
  NullComponent PAUSE_PLAYER = direct("mcav.command.video.pause");

  /**
   * Key {@code mcav.command.video.start}: tells the sender of a video command that the video is playing.
   */
  NullComponent START_VIDEO = direct("mcav.command.video.start");

  /**
   * Key {@code mcav.command.video.info}: sent to every viewer while a video command resolves the media, which can
   * take a few seconds for websites that go through yt-dlp.
   */
  NullComponent LOAD_VIDEO = direct("mcav.command.video.info");

  /**
   * Key {@code mcav.command.browser.release}: {@code /mcav browser release} closed the browser.
   */
  NullComponent RELEASE_BROWSER = direct("mcav.command.browser.release");

  /**
   * Key {@code mcav.command.browser.start}: the browser of {@code /mcav browser create} started and now shows on the
   * maps.
   */
  NullComponent START_BROWSER = direct("mcav.command.browser.start");

  /**
   * Key {@code mcav.command.mrl.error}: the media given to a video or image command could not be found or played,
   * for example a missing file, a broken URL, or an animated GIF given to an image command.
   */
  NullComponent UNSUPPORTED_MRL = direct("mcav.command.mrl.error");

  /**
   * Key {@code mcav.command.url.error}: the address given to {@code /mcav browser create} is not an {@code http} or
   * {@code https} URL.
   */
  NullComponent UNSUPPORTED_URL = direct("mcav.command.url.error");

  /**
   * Key {@code mcav.command.dimension.error}: a resolution or size argument is not written as
   * {@code <width>x<height>} with positive whole numbers.
   */
  NullComponent UNSUPPORTED_DIMENSION = direct("mcav.command.dimension.error");

  /**
   * Key {@code mcav.command.map.error}: the map ids of {@code /mcav screen} lie too far past the maps the world
   * already has, or one of them belonged to a map that no longer exists.
   */
  NullComponent UNSUPPORTED_MAP_ID = direct("mcav.command.map.error");

  /**
   * Key {@code mcav.command.flags.error}: the {@code --yt-dlp} options of a video command name an option the commands
   * do not accept. The argument is why they were refused, inserted at {@code <arg:0>}.
   */
  UniComponent<String> UNSUPPORTED_FLAGS = direct("mcav.command.flags.error", null);

  /**
   * Key {@code mcav.command.vm.flags.error}: the QEMU options of {@code /mcav vm create} name an option the command
   * does not accept, or a disk image outside the image folder of the plugin. The argument is why they were refused,
   * inserted at {@code <arg:0>}.
   */
  UniComponent<String> UNSUPPORTED_VM_FLAGS = direct("mcav.command.vm.flags.error", null);

  /**
   * Key {@code mcav.command.dump.result}: {@code /mcav dump} uploaded the diagnostic dump. The argument is its link,
   * inserted at {@code $URL$}, to share when asking for support.
   */
  UniComponent<String> SEND_DUMP = direct("mcav.command.dump.result", null);

  /**
   * Key {@code mcav.command.dump.load}: sent as soon as {@code /mcav dump} starts collecting and uploading the dump.
   */
  NullComponent CREATE_DUMP = direct("mcav.command.dump.load");

  /**
   * Key {@code mcav.command.dump.error}: the dump of {@code /mcav dump} could not be uploaded; the console has the
   * error.
   */
  NullComponent DUMP_FAILED = direct("mcav.command.dump.error");

  /**
   * Key {@code mcav.command.browser.error}: the browser of {@code /mcav browser create} failed to start, for example
   * because Chrome is not installed; the console has the error.
   */
  NullComponent BROWSER_ERROR = direct("mcav.command.browser.error");

  /**
   * Key {@code mcav.command.video.start.error}: a video command failed while starting the video; the console has the
   * error.
   */
  NullComponent VIDEO_START_ERROR = direct("mcav.command.video.start.error");

  /**
   * Key {@code mcav.command.vm.error}: the virtual machine of {@code /mcav vm create} failed to start for a reason
   * other than a missing QEMU program; the console has the error.
   */
  NullComponent VM_ERROR = direct("mcav.command.vm.error");

  /**
   * Key {@code mcav.command.audio.not_ready}: a video command chose the Discord bot or the HTTP server as the audio
   * output, which is enabled but still starting or failed to start.
   */
  NullComponent AUDIO_NOT_READY = direct("mcav.command.audio.not_ready");

  /**
   * Key {@code mcav.command.scoreboard.lines.error}: a scoreboard video or image asked for more lines than the
   * sidebar can show.
   */
  NullComponent SCOREBOARD_LINES = direct("mcav.command.scoreboard.lines.error");
}
