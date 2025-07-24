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

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.svc.SVCModule;

public final class MCAVVoiceChatPlugin implements VoicechatPlugin {

  private final MCAVSandbox sandbox;

  public MCAVVoiceChatPlugin(final MCAVSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public String getPluginId() {
    return "mcav";
  }

  @Override
  public void initialize(final VoicechatApi api) {
    final MCAVApi mcavApi = this.sandbox.getMCAV();
    final SVCModule svcModule = mcavApi.getModule(SVCModule.class);
    svcModule.inject((VoicechatServerApi) api);
  }
}
