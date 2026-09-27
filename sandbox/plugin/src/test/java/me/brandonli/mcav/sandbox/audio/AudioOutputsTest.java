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
package me.brandonli.mcav.sandbox.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AudioOutputs}.
 */
final class AudioOutputsTest {

  private final AudioProvider provider = mock(AudioProvider.class);

  @Test
  void anOutputThatIsOffOrStillStartingCannotPlay() {
    assertEquals(Message.UNSUPPORTED_AUDIO.build(), AudioOutputs.findProblem(this.provider, AudioArgument.DISCORD_BOT));
    when(this.provider.isHttpEnabled()).thenReturn(true);
    assertEquals(Message.AUDIO_NOT_READY.build(), AudioOutputs.findProblem(this.provider, AudioArgument.HTTP_SERVER));
    when(this.provider.isHttpReady()).thenReturn(true);
    assertNull(AudioOutputs.findProblem(this.provider, AudioArgument.HTTP_SERVER));
    assertNull(AudioOutputs.findProblem(this.provider, AudioArgument.NONE));
    assertNull(AudioOutputs.findProblem(this.provider, AudioArgument.SIMPLE_VOICE_CHAT));
  }

  @Test
  void theViewersGetTheLinkOfOutputsThatHaveOne() {
    when(this.provider.constructVoiceChannelUrl()).thenReturn("https://discord.com/channels/1/2");
    final Player viewer = mock(Player.class);
    AudioOutputs.sendLink(this.provider, AudioArgument.DISCORD_BOT, new Player[] { viewer });
    verify(viewer).sendMessage(Message.AUDIO_DISCORD.build("https://discord.com/channels/1/2"));
    final Player silent = mock(Player.class);
    AudioOutputs.sendLink(this.provider, AudioArgument.NONE, new Player[] { silent });
    verifyNoInteractions(silent);
    when(this.provider.constructHttpUrl()).thenReturn("http://mc.example.com:3000");
    assertEquals(Message.AUDIO_HTTP.build("http://mc.example.com:3000"), AudioOutputs.createLink(this.provider, AudioArgument.HTTP_SERVER));
    assertNull(AudioOutputs.createLink(this.provider, AudioArgument.SIMPLE_VOICE_CHAT));
  }

  @Test
  void theOutputsAreNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(AudioOutputs.class);
  }
}
