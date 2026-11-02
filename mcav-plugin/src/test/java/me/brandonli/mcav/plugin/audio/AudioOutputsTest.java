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
package me.brandonli.mcav.plugin.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class AudioOutputsTest {

  private final AudioProvider provider = mock(AudioProvider.class);

  @Test
  void shouldNotPlayAnOutputThatIsOffOrStillStarting() {
    final Component expected = Message.UNSUPPORTED_AUDIO.build();
    final Optional<Component> problem = AudioOutputs.findProblem(this.provider, AudioArgument.DISCORD_BOT);
    assertEquals(Optional.of(expected), problem);

    when(this.provider.isHttpEnabled()).thenReturn(true);

    final Component AUDIO_NOT_READYExpected = Message.AUDIO_NOT_READY.build();
    final Optional<Component> audioOutputsProblem = AudioOutputs.findProblem(this.provider, AudioArgument.HTTP_SERVER);
    assertEquals(Optional.of(AUDIO_NOT_READYExpected), audioOutputsProblem);

    when(this.provider.isHttpReady()).thenReturn(true);

    final Optional<Component> secondProblem = AudioOutputs.findProblem(this.provider, AudioArgument.HTTP_SERVER);
    assertEquals(Optional.empty(), secondProblem);

    final Optional<Component> thirdProblem = AudioOutputs.findProblem(this.provider, AudioArgument.NONE);
    assertEquals(Optional.empty(), thirdProblem);

    final Optional<Component> fourthProblem = AudioOutputs.findProblem(this.provider, AudioArgument.SIMPLE_VOICE_CHAT);
    assertEquals(Optional.empty(), fourthProblem);
  }

  @Test
  void shouldGiveTheViewersTheLinkOfOutputsThatHaveOne() {
    when(this.provider.constructVoiceChannelUrl()).thenReturn("https://discord.com/channels/1/2");
    final Player viewer = mock(Player.class);
    AudioOutputs.sendLink(this.provider, AudioArgument.DISCORD_BOT, new Player[] { viewer });
    verify(viewer).sendMessage(Message.AUDIO_DISCORD.build("https://discord.com/channels/1/2"));

    final Player silent = mock(Player.class);
    AudioOutputs.sendLink(this.provider, AudioArgument.NONE, new Player[] { silent });
    verifyNoInteractions(silent);

    when(this.provider.constructHttpUrl()).thenReturn("http://mc.example.com:3000");

    final Component expected = Message.AUDIO_HTTP.build("http://mc.example.com:3000");
    final Optional<Component> link = AudioOutputs.createLink(this.provider, AudioArgument.HTTP_SERVER);
    assertEquals(Optional.of(expected), link);

    final Optional<Component> audioOutputsLink = AudioOutputs.createLink(this.provider, AudioArgument.SIMPLE_VOICE_CHAT);
    assertEquals(Optional.empty(), audioOutputsLink);
  }

  @Test
  void shouldRefuseToInstantiateTheOutputs() {
    UtilityClassAssertions.assertNotInstantiable(AudioOutputs.class);
  }
}
