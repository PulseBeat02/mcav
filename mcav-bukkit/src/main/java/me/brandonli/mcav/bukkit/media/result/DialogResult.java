/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.bukkit.media.result;

import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;

public class DialogResult implements FunctionalVideoFilter {

  @Override
  public void start() {
    final CommonDialogData data = new CommonDialogData(
      Component.empty(),
      Optional.empty(),
      true,
      false,
      DialogAction.NONE,
      List.of(),
      List.of()
    );
  }

  @Override
  public void release() {}

  @Override
  public void applyFilter(final ImageBuffer samples, final VideoMetadata metadata) {
    //    final int chatWidth = this.configuration.getChatWidth();
    //    final int chatHeight = this.configuration.getChatHeight();
    //    final String character = this.configuration.getCharacter();
    //    final Collection<UUID> viewers = this.configuration.getViewers();
    //    final ResizeFilter resize = new ResizeFilter(chatWdith, chatHeight);
    //    resize.applyFilter(data, metadata);
    //    final int[] resizedData = data.getPixels();
    //    final Component msg = ChatUtils.createChatComponent(resizedData, character, chatWdith, chatHeight);
    //    final PlainMessage packet = new PlainMessage(msg, chatWidth);
  }
}
