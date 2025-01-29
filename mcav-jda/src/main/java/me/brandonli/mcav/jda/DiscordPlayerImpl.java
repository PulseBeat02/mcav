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
package me.brandonli.mcav.jda;

import java.nio.ByteBuffer;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.utils.natives.ByteUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.managers.Presence;

/**
 * The concrete implementation of {@link DiscordPlayer}, which provides audio playback functionality for Discord
 * voice channels.
 */
public class DiscordPlayerImpl implements DiscordPlayer {

  private static final int TWENTY_MS_SIZE = (48000 * 2 * 2 * 20) / 1000;

  private final ByteBuffer buffer;
  private final JDA jda;

  DiscordPlayerImpl(final JDA jda) {
    this.buffer = ByteBuffer.allocate(1024 * 1024);
    this.jda = jda;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean canProvide() {
    return this.buffer.position() >= TWENTY_MS_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ByteBuffer provide20MsAudio() {
    if (!this.canProvide()) {
      return ByteBuffer.allocate(0);
    }

    final byte[] audioChunk = new byte[TWENTY_MS_SIZE];
    this.buffer.flip();
    this.buffer.get(audioChunk, 0, TWENTY_MS_SIZE);
    this.buffer.compact();

    return ByteBuffer.wrap(audioChunk);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    final ByteBuffer clamped = ByteUtils.clampNormalBufferToBigEndian(samples);
    if (this.buffer.remaining() >= clamped.remaining()) {
      this.buffer.put(clamped);
    } else {
      this.buffer.clear();
      this.buffer.put(clamped);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCurrentMedia(final URLParseDump dump) {
    final Presence presence = this.jda.getPresence();
    presence.setStatus(OnlineStatus.ONLINE);
    presence.setActivity(Activity.playing(dump.title));
  }
}
