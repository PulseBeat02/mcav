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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.svc.SVCModule;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MCAVVoiceChatPlugin}.
 */
final class MCAVVoiceChatPluginTest {

  @Test
  void isNamedAfterThePlugin() {
    final MCAVSandbox sandbox = mock(MCAVSandbox.class);
    final MCAVVoiceChatPlugin plugin = new MCAVVoiceChatPlugin(sandbox);
    final String id = plugin.getPluginId();
    assertEquals("mcav", id);
  }

  @Test
  void handsTheVoiceChatServerToTheLibrary() {
    final MCAVSandbox sandbox = mock(MCAVSandbox.class);
    final MCAVApi library = mock(MCAVApi.class);
    final SVCModule module = mock(SVCModule.class);
    when(sandbox.getMCAV()).thenReturn(library);
    when(library.getModule(SVCModule.class)).thenReturn(module);
    final VoicechatServerApi api = mock(VoicechatServerApi.class);
    final MCAVVoiceChatPlugin plugin = new MCAVVoiceChatPlugin(sandbox);
    plugin.initialize(api);
    verify(module).inject(api);
  }

  @Test
  void rejectsMissingArguments() {
    final MCAVSandbox sandbox = mock(MCAVSandbox.class);
    final MCAVVoiceChatPlugin plugin = new MCAVVoiceChatPlugin(sandbox);
    assertThrows(NullPointerException.class, () -> new MCAVVoiceChatPlugin(null));
    assertThrows(NullPointerException.class, () -> plugin.initialize(null));
  }
}
