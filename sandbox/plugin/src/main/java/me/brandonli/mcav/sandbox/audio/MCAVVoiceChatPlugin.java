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

import com.google.common.base.Preconditions;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.svc.SVCModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin MCAV registers with Simple Voice Chat. Once Simple Voice Chat has started, it hands its server API to
 * the {@link SVCModule} of the library, which voice chat audio needs before it can play.
 */
public final class MCAVVoiceChatPlugin implements VoicechatPlugin {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAVVoiceChatPlugin.class);
  private static final String PLUGIN_ID = "mcav";

  private final MCAVSandbox sandbox;

  /**
   * Constructs the voice chat plugin.
   *
   * @param sandbox the plugin whose library receives the voice chat server API
   */
  public MCAVVoiceChatPlugin(final MCAVSandbox sandbox) {
    Preconditions.checkNotNull(sandbox, "Plugin must not be null");
    this.sandbox = sandbox;
  }

  /**
   * Gets the id Simple Voice Chat knows this plugin by.
   *
   * @return {@code mcav}
   */
  @Override
  public String getPluginId() {
    return PLUGIN_ID;
  }

  /**
   * Hands the voice chat server API to the {@link SVCModule} of the library. Simple Voice Chat calls this once it has
   * started on the server, where the API it passes is always a {@link VoicechatServerApi}.
   *
   * @param api the voice chat API
   */
  @Override
  public void initialize(final VoicechatApi api) {
    Preconditions.checkNotNull(api, "Voice chat API must not be null");
    final MCAVApi library = this.sandbox.getMCAV();
    final SVCModule module = library.getModule(SVCModule.class);
    final VoicechatServerApi serverApi = (VoicechatServerApi) api;
    module.inject(serverApi);
    LOGGER.info("Simple Voice Chat audio is ready");
  }
}
