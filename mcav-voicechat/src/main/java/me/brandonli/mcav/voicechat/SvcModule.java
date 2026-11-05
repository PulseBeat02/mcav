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
package me.brandonli.mcav.voicechat;

import com.google.common.base.Preconditions;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import java.util.Optional;
import me.brandonli.mcav.module.MCAVModule;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Connects MCAV to Simple Voice Chat.
 *
 * <p>Install it with {@code MCAV.api().install(SvcModule.class)}, then hand it the server API from the
 * {@code initialize} method of your voice chat plugin. Create {@link SvcFilter}s only after that, because they need
 * the API.
 *
 * <p>The injected API is shared by every instance of the module and stays until {@link #stop()}. This class is
 * thread-safe.
 *
 * {@snippet :
 * public void initialize(final VoicechatApi api) {
 *   final VoicechatServerApi serverApi = (VoicechatServerApi) api;
 *   final SvcModule module = this.mcav.getModule(SvcModule.class);
 *   module.inject(serverApi);
 * }
 * }
 *
 * @since 1.0.0
 */
public final class SvcModule implements MCAVModule {

  private static final String MODULE_NAME = "svc";

  private static volatile @Nullable VoicechatServerApi voiceChatApi;

  /**
   * Creates the module. The module loader calls it for you.
   *
   * @since 1.0.0
   */
  public SvcModule() {}

  /**
   * Starts the module. Nothing is prepared here, because the voice chat API only becomes available once your voice
   * chat plugin calls {@link #inject(VoicechatServerApi)}.
   *
   * @since 1.0.0
   */
  @Override
  public void start() {}

  /**
   * Stops the module and forgets the injected voice chat API, so filters can no longer be created or started until
   * an API is injected again. Existing filters keep their speakers and must be released by their callers.
   *
   * @since 1.0.0
   */
  @Override
  public void stop() {
    voiceChatApi = null;
  }

  /**
   * Hands the Simple Voice Chat server API to MCAV. Call it from the {@code initialize} method of your voice chat
   * plugin.
   *
   * @param api the non-null server API, borrowed until {@link #stop()}; it replaces the API shared by
   *            all module instances
   * @throws NullPointerException if {@code api} is null
   * @since 1.0.0
   */
  public void inject(final VoicechatServerApi api) {
    Preconditions.checkNotNull(api, "Voice chat API must not be null!");

    voiceChatApi = api;
  }

  static VoicechatServerApi requireVoiceChatApi() {
    final VoicechatServerApi api = voiceChatApi;
    if (api == null) {
      final String message = "The Simple Voice Chat API was not injected; call SvcModule#inject from your voice chat plugin!";
      throw new IllegalStateException(message);
    }

    return api;
  }

  /**
   * Returns the injected server API.
   *
   * @return the shared API snapshot, or empty before injection or after {@link #stop()}
   * @since 1.0.0
   */
  public static Optional<VoicechatServerApi> getVoiceChatApi() {
    return Optional.ofNullable(voiceChatApi);
  }

  /**
   * Returns the name of the module.
   *
   * @return {@code "svc"}
   * @since 1.0.0
   */
  @Override
  public String getModuleName() {
    return MODULE_NAME;
  }
}
