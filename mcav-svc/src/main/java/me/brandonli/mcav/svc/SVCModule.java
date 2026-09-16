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
package me.brandonli.mcav.svc;

import com.google.common.base.Preconditions;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import me.brandonli.mcav.module.MCAVModule;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Connects the library to Simple Voice Chat. Install it with {@code MCAV.api().install(SVCModule.class)}, then
 * hand it the server API from your voice chat plugin once the voice chat server started:
 *
 * <pre><code>
 *   public void initialize(final VoicechatApi api) {
 *     final SVCModule module = mcav.getModule(SVCModule.class);
 *     module.inject((VoicechatServerApi) api);
 *   }
 * </code></pre>
 */
public final class SVCModule implements MCAVModule {

  private static volatile @Nullable VoicechatServerApi voiceChatApi;

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public SVCModule() {
    // the API is injected later
  }

  /**
   * Hands the Simple Voice Chat server API to the library. Call it from the {@code initialize} method of your
   * voice chat plugin.
   *
   * @param api the server API
   */
  public void inject(final VoicechatServerApi api) {
    Preconditions.checkNotNull(api, "Voice chat API must not be null");
    voiceChatApi = api;
  }

  /**
   * Gets the injected server API.
   *
   * @return the API, or null if it was not injected yet
   */
  public static @Nullable VoicechatServerApi getVoiceChatApi() {
    return voiceChatApi;
  }

  /**
   * Gets the injected server API, failing if it is missing.
   *
   * @return the API
   * @throws IllegalStateException if {@link #inject(VoicechatServerApi)} was not called yet
   */
  static VoicechatServerApi requireVoiceChatApi() {
    final VoicechatServerApi api = voiceChatApi;
    if (api == null) {
      throw new IllegalStateException("The Simple Voice Chat API was not injected; call SVCModule#inject from your voice chat plugin");
    }
    return api;
  }

  /**
   * Starts the module. Nothing is prepared here, because the voice chat API only becomes available once your voice
   * chat plugin calls {@link #inject(VoicechatServerApi)}.
   */
  @Override
  public void start() {
    // nothing to prepare until the API is injected
  }

  /**
   * Stops the module and forgets the injected voice chat API, so filters can no longer be created or started until
   * an API is injected again.
   */
  @Override
  public void stop() {
    voiceChatApi = null;
  }

  /**
   * Gets the name of the module.
   *
   * @return {@code "svc"}
   */
  @Override
  public String getModuleName() {
    return "svc";
  }
}
