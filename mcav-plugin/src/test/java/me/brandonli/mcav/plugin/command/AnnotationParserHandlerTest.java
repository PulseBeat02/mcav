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
package me.brandonli.mcav.plugin.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.image.ImageManager;
import me.brandonli.mcav.plugin.command.interaction.BrowserCommand;
import me.brandonli.mcav.plugin.command.interaction.VirtualizeCommand;
import me.brandonli.mcav.plugin.command.interaction.VncCommand;
import me.brandonli.mcav.plugin.command.video.VideoPlayerManager;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.TestCommandManager;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.MapCodec;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.description.CommandDescription;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.execution.CommandExecutor;
import org.incendo.cloud.execution.CommandResult;
import org.incendo.cloud.minecraft.extras.RichDescription;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.flag.CommandFlagParser;
import org.incendo.cloud.parser.standard.EnumParser;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.type.range.IntRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AnnotationParserHandler} by parsing every command of the plugin into a command manager that
 * registers nothing on a server.
 */
final class AnnotationParserHandlerTest {

  private static final List<String> EXPECTED_SYNTAXES = List.of(
    "mcav dump",
    "mcav help query",
    "mcav screen blockDimensions mapId material location",
    "mcav image release",
    "mcav image map playerSelector imageResolution blockDimensions mapId ditheringAlgorithm mrl flags",
    "mcav video pause",
    "mcav video resume",
    "mcav video release",
    "mcav video seek position",
    "mcav video volume percent",
    "mcav video speed factor",
    "mcav video loop enabled",
    "mcav video devices",
    "mcav video hologram set location",
    "mcav video hologram disable",
    "mcav video map playerSelector playerType audioType videoResolution blockDimensions mapId ditheringAlgorithm ytDlpOptions mrl flags",
    "mcav video mcv2 playerSelector playerType audioType videoResolution blockDimensions mapId profile ditheringAlgorithm ytDlpOptions mrl flags",
    "mcav mcv2 play playerSelector blockDimensions mapId ticks file",
    "mcav mcv2 stream playerSelector blockDimensions mapId fps file",
    "mcav mcv2 stop",
    "mcav browser interact",
    "mcav browser release",
    "mcav browser create playerSelector browserResolution nth blockDimensions mapId ditheringAlgorithm audioType url flags",
    "mcav vm interact",
    "mcav vm release",
    "mcav vm create playerSelector vmResolution targetFps blockDimensions mapId ditheringAlgorithm architecture audioType flags",
    "mcav vnc interact",
    "mcav vnc release",
    "mcav vnc create playerSelector vncResolution targetFps blockDimensions mapId ditheringAlgorithm server flags"
  );

  private MCAVSandbox plugin;

  private VideoPlayerManager videoManager;

  private TestCommandManager commands;

  private LegacyPaperCommandManager<CommandSender> paperManager;

  private MockedStatic<LegacyPaperCommandManager<CommandSender>> paperManagers;

  @BeforeEach
  void mockServer() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getServer()).thenReturn(server);
    this.videoManager = mock(VideoPlayerManager.class);
    final ImageManager imageManager = mock(ImageManager.class);
    final AudioProvider audioProvider = mock(AudioProvider.class);
    when(this.plugin.getVideoPlayerManager()).thenReturn(this.videoManager);
    when(this.plugin.getImageManager()).thenReturn(imageManager);
    when(this.plugin.getAudioProvider()).thenReturn(audioProvider);
    this.commands = new TestCommandManager();
    this.paperManager = this.commands.asPaperManager();
    this.paperManagers = Mockito.mockStatic();
    this.paperManagers.when(() -> LegacyPaperCommandManager.createNative(eq(this.plugin), notNull())).thenReturn(this.paperManager);
  }

  @AfterEach
  void closeMocks() {
    this.paperManagers.close();
  }

  @Test
  void usesThePaperCommandManagerOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    final CommandManager<CommandSender> manager = handler.getManager();
    assertSame(this.paperManager, manager);
    verify(this.paperManager, never()).registerBrigadier();
  }

  @Test
  void registersBrigadierWhenPaperSupportsIt() {
    doReturn(true).when(this.paperManager).hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER);
    doNothing().when(this.paperManager).registerBrigadier();
    new AnnotationParserHandler(this.plugin);
    verify(this.paperManager).registerBrigadier();
  }

  @Test
  void registersEveryCommandOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final Set<String> syntaxes = this.commands.syntaxes();
    final int count = syntaxes.size();
    final String registeredSyntaxes = String.valueOf(syntaxes);
    assertEquals(39, count, registeredSyntaxes);
    for (final String syntax : EXPECTED_SYNTAXES) {
      final boolean registered = syntaxes.contains(syntax);
      assertTrue(registered, syntax + " in " + syntaxes);
    }

    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager).registerEvents(any(BrowserCommand.class), eq(this.plugin));
    verify(pluginManager).registerEvents(any(VirtualizeCommand.class), eq(this.plugin));
    verify(pluginManager).registerEvents(any(VncCommand.class), eq(this.plugin));
  }

  /**
   * Finds the parser of a named argument of a registered command.
   */
  private ArgumentParser<CommandSender, ?> parserOf(final String syntax, final String argument) {
    final Command<CommandSender> command = this.commands.command(syntax);
    final List<CommandComponent<CommandSender>> components = command.components();
    for (final CommandComponent<CommandSender> component : components) {
      final String name = component.name();
      if (name.equals(argument)) {
        return component.parser();
      }
    }
    throw new AssertionError("No argument " + argument + " in " + syntax);
  }

  @Test
  void limitsTheFrameRateOfVirtualMachines() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String syntax =
      "mcav vm create playerSelector vmResolution targetFps blockDimensions mapId ditheringAlgorithm architecture audioType flags";
    final ArgumentParser<CommandSender, ?> parser = this.parserOf(syntax, "targetFps");
    final IntegerParser<?> fps = assertInstanceOf(IntegerParser.class, parser);
    final IntRange range = fps.range();
    final int min = range.minInt();
    final int max = range.maxInt();
    assertEquals(1, min);
    assertEquals(240, max, "a capture rate above the largest suggestion only burns the CPU of the server");
  }

  @Test
  void takesTheRestOfTheLineForFreeTextArguments() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String vm =
      "mcav vm create playerSelector vmResolution targetFps blockDimensions mapId ditheringAlgorithm architecture audioType flags";
    final String image = "mcav image map playerSelector imageResolution blockDimensions mapId ditheringAlgorithm mrl flags";
    final String browser =
      "mcav browser create playerSelector browserResolution nth blockDimensions mapId ditheringAlgorithm audioType url flags";
    final ArgumentParser<CommandSender, ?> flags = this.parserOf(vm, "flags");
    final ArgumentParser<CommandSender, ?> mrl = this.parserOf(image, "mrl");
    final ArgumentParser<CommandSender, ?> url = this.parserOf(browser, "url");
    final StringParser<?> flagsParser = assertInstanceOf(StringParser.class, flags);
    final StringParser<?> mrlParser = assertInstanceOf(StringParser.class, mrl);
    final StringParser<?> urlParser = assertInstanceOf(StringParser.class, url);
    assertEquals(StringParser.StringMode.GREEDY, flagsParser.stringMode(), "QEMU options are several words, as the jukebox sends them");
    assertEquals(
      StringParser.StringMode.GREEDY_FLAG_YIELDING,
      mrlParser.stringMode(),
      "image paths may contain spaces without quotes, up to the codec flag"
    );
    assertEquals(StringParser.StringMode.GREEDY_FLAG_YIELDING, urlParser.stringMode());
  }

  @Test
  void namesEveryArgumentOfACommandOnce() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    for (final String syntax : this.commands.syntaxes()) {
      // an argument named like the component of the command's flags would be handed the flags
      final List<String> names = this.commands.command(syntax).components().stream().map(CommandComponent::name).toList();
      assertEquals(names.size(), Set.copyOf(names).size(), syntax);
    }
  }

  @Test
  void takesTheVncServerUnquotedUpToTheFlags() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String vnc = "mcav vnc create playerSelector vncResolution targetFps blockDimensions mapId ditheringAlgorithm server flags";
    final StringParser<?> server = assertInstanceOf(StringParser.class, this.parserOf(vnc, "server"));
    // a quoted argument ends a host at its colon
    assertEquals(StringParser.StringMode.GREEDY_FLAG_YIELDING, server.stringMode());
  }

  @Test
  void everyWallOfMapsTakesTheCodecFlag() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    for (final String syntax : List.of(
      "mcav video map playerSelector playerType audioType videoResolution blockDimensions mapId ditheringAlgorithm ytDlpOptions mrl flags",
      "mcav image map playerSelector imageResolution blockDimensions mapId ditheringAlgorithm mrl flags",
      "mcav browser create playerSelector browserResolution nth blockDimensions mapId ditheringAlgorithm audioType url flags",
      "mcav vnc create playerSelector vncResolution targetFps blockDimensions mapId ditheringAlgorithm server flags"
    )) {
      final Command<CommandSender> command = this.commands.command(syntax);
      final CommandFlagParser<?> flags = assertInstanceOf(CommandFlagParser.class, command.components().getLast().parser(), syntax);
      final List<String> names = flags.flags().stream().map(CommandFlag::name).toList();
      assertEquals("codec", names.getFirst(), syntax);
      final CommandComponent<?> codec = Objects.requireNonNull(flags.flags().iterator().next().commandComponent());
      final EnumParser<?, ?> values = assertInstanceOf(EnumParser.class, codec.parser(), syntax);
      assertEquals(Set.of(MapCodec.DITHER, MapCodec.MCV2), Set.copyOf(values.acceptedValues()), syntax);
    }
  }

  @Test
  void takesASeekPositionWithColonsUnquoted() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final StringParser<?> position = assertInstanceOf(StringParser.class, this.parserOf("mcav video seek position", "position"));
    assertEquals(StringParser.StringMode.GREEDY, position.stringMode(), "a single word cannot hold the colon of 1:30");
  }

  @Test
  void everyVideoAndImageCommandTakesQuotedFilters() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final List<String> media = this.commands
      .syntaxes()
      .stream()
      .filter(syntax -> (syntax.startsWith("mcav video ") || syntax.startsWith("mcav image ")) && syntax.contains(" mrl"))
      .toList();
    assertEquals(11, media.size(), String.valueOf(media));
    for (final String syntax : media) {
      final Command<CommandSender> command = this.commands.command(syntax);
      final CommandFlagParser<?> flags = assertInstanceOf(CommandFlagParser.class, command.components().getLast().parser(), syntax);
      final CommandFlag<?> filters = flags
        .flags()
        .stream()
        .filter(flag -> flag.name().equals("filters"))
        .findFirst()
        .orElseThrow();
      final StringParser<?> parser = assertInstanceOf(
        StringParser.class,
        Objects.requireNonNull(filters.commandComponent()).parser(),
        syntax
      );
      assertEquals(StringParser.StringMode.QUOTED, parser.stringMode(), syntax);
    }
  }

  @Test
  void theBrowserAndTheMachineChooseTheirAudioOutputLikeTheVideos() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String video =
      "mcav video map playerSelector playerType audioType videoResolution blockDimensions mapId ditheringAlgorithm ytDlpOptions mrl flags";
    final String browser =
      "mcav browser create playerSelector browserResolution nth blockDimensions mapId ditheringAlgorithm audioType url flags";
    final String vm =
      "mcav vm create playerSelector vmResolution targetFps blockDimensions mapId ditheringAlgorithm architecture audioType flags";
    final ArgumentParser<CommandSender, ?> videoAudio = this.parserOf(video, "audioType");
    for (final String syntax : List.of(browser, vm)) {
      final ArgumentParser<CommandSender, ?> audio = this.parserOf(syntax, "audioType");
      final EnumParser<?, ?> outputs = assertInstanceOf(EnumParser.class, audio);
      // tab completion offers every output, as for the videos
      assertEquals(assertInstanceOf(EnumParser.class, videoAudio).acceptedValues(), outputs.acceptedValues(), syntax);
    }
    final Component description = ((RichDescription) this.commands.command(browser).commandDescription().description()).contents();
    assertEquals("Opens a web page on a wall of maps, with its sound in an audio output", Components.plain(description));
  }

  @Test
  void describesTheCommandsInTheLanguageOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final Command<CommandSender> dump = this.commands.command("mcav dump");
    final CommandDescription commandDescription = dump.commandDescription();
    final Description description = commandDescription.description();
    final RichDescription rich = (RichDescription) description;
    final Component contents = rich.contents();
    final String text = Components.plain(contents);
    assertEquals("Creates a dump of the current server information", text);
  }

  @Test
  void runsTheParsedCommands() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class);
    when(this.videoManager.getPlayer()).thenReturn(player);
    final CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission(anyString())).thenReturn(true);
    final CommandExecutor<CommandSender> executor = this.commands.commandExecutor();
    final CompletableFuture<CommandResult<CommandSender>> execution = executor.executeCommand(sender, "mcav video pause");
    execution.join();
    verify(player).pause();
    final List<Component> messages = Components.received(sender);
    final Component paused = Message.PAUSE_PLAYER.build();
    assertEquals(List.of(paused), messages);
  }

  @Test
  void shutsEveryFeatureDown() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      handler.shutdownCommands();
      handlers.verify(() -> HandlerList.unregisterAll(any(BrowserCommand.class)));
      handlers.verify(() -> HandlerList.unregisterAll(any(VirtualizeCommand.class)));
    }
  }

  @Test
  void rollsBackAllFeaturesWhenRegistrationFails() {
    final IllegalStateException failure = new IllegalStateException("listener registration failed");
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        Mockito.doThrow(failure).when(browser).registerFeature(any())
      );
      final MockedConstruction<VirtualizeCommand> machines = Mockito.mockConstruction(VirtualizeCommand.class)
    ) {
      final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, handler::registerCommands);
      assertSame(failure, thrown);
      handler.shutdownCommands();
      final List<BrowserCommand> createdBrowsers = browsers.constructed();
      final List<VirtualizeCommand> createdMachines = machines.constructed();
      final BrowserCommand browser = createdBrowsers.getFirst();
      final VirtualizeCommand machine = createdMachines.getFirst();
      verify(browser, Mockito.times(1)).shutdown();
      verify(machine, Mockito.times(1)).shutdown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void registrationFailureRemainsPrimaryWhenRollbackAlsoFails(final boolean sameFailure) {
    final IllegalStateException primary = new IllegalStateException("registration failed");
    final RuntimeException cleanup = sameFailure ? primary : new IllegalArgumentException("browser cleanup failed");
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, context) -> {
        Mockito.doThrow(primary).when(browser).registerFeature(any());
        Mockito.doThrow(cleanup).when(browser).shutdown();
      });
      final MockedConstruction<VirtualizeCommand> machines = Mockito.mockConstruction(VirtualizeCommand.class)
    ) {
      final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, handler::registerCommands);
      assertSame(primary, thrown);
      final Throwable[] suppressed = thrown.getSuppressed();
      final Throwable[] expected = sameFailure ? new Throwable[0] : new Throwable[] { cleanup };
      assertArrayEquals(expected, suppressed);
      handler.shutdownCommands();
      final List<BrowserCommand> createdBrowsers = browsers.constructed();
      final List<VirtualizeCommand> createdMachines = machines.constructed();
      final BrowserCommand browser = createdBrowsers.getFirst();
      final VirtualizeCommand machine = createdMachines.getFirst();
      verify(browser, Mockito.times(1)).shutdown();
      verify(machine, Mockito.times(1)).shutdown();
    }
  }

  @Test
  void shutsLaterFeaturesDownEvenWhenAnEarlierFeatureFails() {
    final IllegalStateException failure = new IllegalStateException("browser cleanup failed");
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        Mockito.doThrow(failure).when(browser).shutdown()
      );
      final MockedConstruction<VirtualizeCommand> machines = Mockito.mockConstruction(VirtualizeCommand.class)
    ) {
      final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, handler::shutdownCommands);
      assertSame(failure, thrown);
      handler.shutdownCommands();
      final List<BrowserCommand> createdBrowsers = browsers.constructed();
      final List<VirtualizeCommand> createdMachines = machines.constructed();
      final BrowserCommand browser = createdBrowsers.getFirst();
      final VirtualizeCommand machine = createdMachines.getFirst();
      verify(browser, Mockito.times(1)).shutdown();
      verify(machine, Mockito.times(1)).shutdown();
    }
  }

  @Test
  void rendersDescriptionsWithTheMessagesOfThePlugin() {
    final RichDescription description = AnnotationParserHandler.describe("mcav.command.video.pause.info");
    final Component contents = description.contents();
    final String text = Components.plain(contents);
    assertEquals("Pauses the current video player", text);
    final RichDescription none = AnnotationParserHandler.describe("");
    final boolean empty = none.isEmpty();
    assertTrue(empty);
    assertThrows(MissingResourceException.class, () -> AnnotationParserHandler.describe("mcav.unknown"));
  }

  @Test
  void refusesANullPlugin() {
    assertThrows(NullPointerException.class, () -> new AnnotationParserHandler(null));
  }

  @Test
  void propagatesFatalRegistrationFailureWithoutAttemptingRollback() {
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        Mockito.doThrow(fatal).when(browser).registerFeature(any())
      );
      final MockedConstruction<VirtualizeCommand> machines = Mockito.mockConstruction(VirtualizeCommand.class)
    ) {
      final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, handler::registerCommands);
      assertSame(fatal, thrown);
      final List<BrowserCommand> createdBrowsers = browsers.constructed();
      final List<VirtualizeCommand> createdMachines = machines.constructed();
      final BrowserCommand browser = createdBrowsers.getFirst();
      final VirtualizeCommand machine = createdMachines.getFirst();
      verify(browser, never()).shutdown();
      verify(machine, never()).shutdown();
    }
  }

  @Test
  void propagatesFatalCleanupFailureDuringRollback() {
    final IllegalStateException primary = new IllegalStateException("registration failed");
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) -> {
        Mockito.doThrow(primary).when(browser).registerFeature(any());
        Mockito.doThrow(fatal).when(browser).shutdown();
      });
      final MockedConstruction<VirtualizeCommand> machines = Mockito.mockConstruction(VirtualizeCommand.class)
    ) {
      final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, handler::registerCommands);
      assertSame(fatal, thrown);
      final List<BrowserCommand> createdBrowsers = browsers.constructed();
      final List<VirtualizeCommand> createdMachines = machines.constructed();
      final BrowserCommand browser = createdBrowsers.getFirst();
      final VirtualizeCommand machine = createdMachines.getFirst();
      verify(browser).shutdown();
      verify(machine, never()).shutdown();
    }
  }
}
