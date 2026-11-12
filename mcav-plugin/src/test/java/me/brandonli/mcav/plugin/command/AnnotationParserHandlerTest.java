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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
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
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
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
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.type.range.IntRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class AnnotationParserHandlerTest {

  private static final Set<String> EXPECTED_SYNTAXES = Set.of(
    "mcav dump",
    "mcav help query",
    "mcav screen blockDimensions mapId material location",
    "mcav image release",
    "mcav image map playerSelector imageResolution blockDimensions mapId ditheringAlgorithm mrl flags",
    "mcav image block playerSelector imageResolution location mrl flags",
    "mcav image chat playerSelector imageResolution character mrl flags",
    "mcav image entity playerSelector imageResolution character location mrl flags",
    "mcav image scoreboard playerSelector imageResolution character mrl flags",
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
    "mcav video map playerSelector playerType audioType videoResolution blockDimensions mapId ditheringAlgorithm ytdlpOptions mrl flags",
    "mcav video mcv2 playerSelector playerType audioType videoResolution blockDimensions mapId preset ditheringAlgorithm ytdlpOptions mrl flags",
    "mcav video block playerSelector playerType audioType videoResolution location ytdlpOptions mrl flags",
    "mcav video chat playerSelector playerType audioType videoResolution character ytdlpOptions mrl flags",
    "mcav video entity playerSelector playerType audioType videoResolution character location ytdlpOptions mrl flags",
    "mcav video scoreboard playerSelector playerType audioType videoResolution character ytdlpOptions mrl flags",
    "mcav mcv2 play playerSelector blockDimensions mapId ticks file",
    "mcav mcv2 stream playerSelector blockDimensions mapId framesPerSecond file",
    "mcav mcv2 stop",
    "mcav mcv2 encode file output resolution preset",
    "mcav mcv2 cancel",
    "mcav browser interact",
    "mcav browser release",
    "mcav browser create playerSelector browserResolution nth blockDimensions mapId ditheringAlgorithm audioType url flags",
    "mcav vm interact",
    "mcav vm release",
    "mcav vm create playerSelector vmResolution targetFramesPerSecond blockDimensions mapId ditheringAlgorithm architecture audioType flags",
    "mcav vnc interact",
    "mcav vnc release",
    "mcav vnc create playerSelector vncResolution targetFramesPerSecond blockDimensions mapId ditheringAlgorithm server flags"
  );

  private static final String BROWSER_SYNTAX =
    "mcav browser create playerSelector browserResolution nth blockDimensions mapId ditheringAlgorithm audioType url flags";
  private static final String IMAGE_SYNTAX =
    "mcav image map playerSelector imageResolution blockDimensions mapId ditheringAlgorithm mrl flags";
  private static final String VIDEO_SYNTAX =
    "mcav video map playerSelector playerType audioType videoResolution blockDimensions mapId ditheringAlgorithm ytdlpOptions mrl flags";
  private static final String VM_SYNTAX =
    "mcav vm create playerSelector vmResolution targetFramesPerSecond blockDimensions mapId ditheringAlgorithm architecture audioType flags";
  private static final String VNC_SYNTAX =
    "mcav vnc create playerSelector vncResolution targetFramesPerSecond blockDimensions mapId ditheringAlgorithm server flags";

  private static final String REGISTRATION_FAILURE_MESSAGE = "registration failed!";

  private static final String BROWSER_CLEANUP_FAILURE_MESSAGE = "browser cleanup failed!";

  private static final String FATAL_FAILURE_MESSAGE = "fatal sentinel!";

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
    this.paperManager = this.commands.createPaperManager();
    this.paperManagers = Mockito.mockStatic();
    this.paperManagers.when(() -> LegacyPaperCommandManager.createNative(eq(this.plugin), notNull())).thenReturn(this.paperManager);
  }

  @AfterEach
  void closeMocks() {
    this.paperManagers.close();
  }

  @Test
  void shouldUseThePaperCommandManagerOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    final CommandManager<CommandSender> manager = handler.getManager();
    assertSame(this.paperManager, manager);
    verify(this.paperManager, never()).registerBrigadier();
  }

  @Test
  void shouldRegisterBrigadierWhenPaperSupportsIt() {
    doReturn(true).when(this.paperManager).hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER);
    doNothing().when(this.paperManager).registerBrigadier();
    new AnnotationParserHandler(this.plugin);
    verify(this.paperManager).registerBrigadier();
  }

  @Test
  void shouldRegisterEveryCommandOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final Set<String> syntaxes = this.commands.getSyntaxes();
    assertEquals(EXPECTED_SYNTAXES, syntaxes);

    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager).registerEvents(any(BrowserCommand.class), eq(this.plugin));
    verify(pluginManager).registerEvents(any(VirtualizeCommand.class), eq(this.plugin));
    verify(pluginManager).registerEvents(any(VncCommand.class), eq(this.plugin));
  }

  @Test
  void shouldCheckAPermissionOfThePluginForEveryCommand() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final Set<String> syntaxes = this.commands.getSyntaxes();

    for (final String syntax : syntaxes) {
      final Command<CommandSender> command = this.commands.getCommand(syntax);
      final Permission permission = command.commandPermission();
      final String name = permission.permissionString();
      final boolean pluginPermission = name.startsWith("mcav.command.");
      assertTrue(pluginPermission, syntax);
    }
  }

  @Test
  void shouldLimitTheFrameRateOfVirtualMachines() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String syntax = VM_SYNTAX;
    final ArgumentParser<CommandSender, ?> parser = this.parserOf(syntax, "targetFramesPerSecond");
    final IntegerParser<?> framesPerSecond = assertInstanceOf(IntegerParser.class, parser);

    final IntRange range = framesPerSecond.range();
    final int minimum = range.minInt();
    final int maximum = range.maxInt();
    assertEquals(1, minimum);
    assertEquals(240, maximum, "a capture rate above the largest suggestion only burns the CPU of the server");
  }

  private ArgumentParser<CommandSender, ?> parserOf(final String syntax, final String argument) {
    final CommandComponent<CommandSender> component = this.componentOf(syntax, argument);
    return component.parser();
  }

  private CommandComponent<CommandSender> componentOf(final String syntax, final String argument) {
    final Command<CommandSender> command = this.commands.getCommand(syntax);
    final List<CommandComponent<CommandSender>> components = command.components();
    for (final CommandComponent<CommandSender> component : components) {
      final String name = component.name();
      if (name.equals(argument)) {
        return component;
      }
    }
    final String message = "Argument %s is missing in syntax %s of the command!".formatted(argument, syntax);
    throw new AssertionError(message);
  }

  @Test
  void shouldTakeTheCharacterOfEveryTextDisplayInQuotes() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final List<String> textDisplays = this.commands
      .getSyntaxes()
      .stream()
      .filter(syntax -> syntax.contains(" character "))
      .toList();

    final int size = textDisplays.size();
    assertEquals(6, size, String.valueOf(textDisplays));

    for (final String syntax : textDisplays) {
      final ArgumentParser<CommandSender, ?> characterParser = this.parserOf(syntax, "character");
      final StringParser<?> parser = assertInstanceOf(StringParser.class, characterParser, syntax);

      final StringParser.StringMode stringMode = parser.stringMode();
      assertEquals(StringParser.StringMode.QUOTED, stringMode, syntax);
    }
  }

  @Test
  void shouldOfferTheChatCommandsTheSizesOfTheChatWindow() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final SuggestionProvider provider = new SuggestionProvider();
    final List<String> expected = provider.suggestChatResolutions().toList();

    final List<String> image = this.suggestionsOf("mcav image chat playerSelector imageResolution character mrl flags", "imageResolution");
    assertEquals(expected, image);

    final List<String> video = this.suggestionsOf(
      "mcav video chat playerSelector playerType audioType videoResolution character ytdlpOptions mrl flags",
      "videoResolution"
    );
    assertEquals(expected, video);
  }

  private List<String> suggestionsOf(final String syntax, final String argument) {
    final CommandComponent<CommandSender> component = this.componentOf(syntax, argument);
    final org.incendo.cloud.suggestion.SuggestionProvider<CommandSender> provider = component.suggestionProvider(); // fqn: clashes with the SuggestionProvider of this package
    final CommandSender sender = mock(CommandSender.class);
    final CommandContext<CommandSender> context = new CommandContext<>(sender, this.commands);
    final CommandInput input = CommandInput.empty();
    final CompletableFuture<? extends Iterable<? extends Suggestion>> future = provider.suggestionsFuture(context, input);
    final Iterable<? extends Suggestion> suggestions = future.join();
    final List<String> values = new ArrayList<>();
    for (final Suggestion suggestion : suggestions) {
      final String value = suggestion.suggestion();
      values.add(value);
    }

    return values;
  }

  @Test
  void shouldOfferTheScoreboardCommandsOnlySizesTheyCanShow() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();

    final List<String> image = this.suggestionsOf(
      "mcav image scoreboard playerSelector imageResolution character mrl flags",
      "imageResolution"
    );
    this.assertFitsAScoreboard(image);

    final List<String> video = this.suggestionsOf(
      "mcav video scoreboard playerSelector playerType audioType videoResolution character ytdlpOptions mrl flags",
      "videoResolution"
    );
    this.assertFitsAScoreboard(video);
  }

  private void assertFitsAScoreboard(final List<String> sizes) {
    final boolean offered = !sizes.isEmpty();
    assertTrue(offered, String.valueOf(sizes));
    for (final String size : sizes) {
      final boolean tooTall = ScoreboardArguments.isTooTall(size);
      assertFalse(tooTall, size);
    }
  }

  @Test
  void shouldTakeTheRestOfTheLineForFreeTextArguments() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String vm = VM_SYNTAX;
    final String image = IMAGE_SYNTAX;
    final String browser = BROWSER_SYNTAX;
    final ArgumentParser<CommandSender, ?> flags = this.parserOf(vm, "flags");
    final ArgumentParser<CommandSender, ?> mrl = this.parserOf(image, "mrl");
    final ArgumentParser<CommandSender, ?> url = this.parserOf(browser, "url");
    final StringParser<?> flagsParser = assertInstanceOf(StringParser.class, flags);
    final StringParser<?> mrlParser = assertInstanceOf(StringParser.class, mrl);
    final StringParser<?> urlParser = assertInstanceOf(StringParser.class, url);

    final StringParser.StringMode stringMode = flagsParser.stringMode();
    assertEquals(StringParser.StringMode.GREEDY, stringMode, "QEMU options are several words, as the jukebox sends them");

    final StringParser.StringMode mrlParserStringMode = mrlParser.stringMode();
    assertEquals(
      StringParser.StringMode.GREEDY_FLAG_YIELDING,
      mrlParserStringMode,
      "image paths may contain spaces without quotes, up to the codec flag"
    );

    final StringParser.StringMode urlParserStringMode = urlParser.stringMode();
    assertEquals(StringParser.StringMode.GREEDY_FLAG_YIELDING, urlParserStringMode);
  }

  @Test
  void shouldNameEveryArgumentOfACommandOnce() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    for (final String syntax : this.commands.getSyntaxes()) {
      // an argument named like the component of the command's flags would be handed the flags
      final List<String> names = this.commands.getCommand(syntax).components().stream().map(CommandComponent::name).toList();

      final int expected = names.size();
      final int size = Set.copyOf(names).size();
      assertEquals(expected, size, syntax);
    }
  }

  @Test
  void shouldTakeTheVncServerUnquotedUpToTheFlags() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String vnc = VNC_SYNTAX;
    final ArgumentParser<CommandSender, ?> serverParser = this.parserOf(vnc, "server");
    final StringParser<?> server = assertInstanceOf(StringParser.class, serverParser);
    // a quoted argument ends a host at its colon

    final StringParser.StringMode stringMode = server.stringMode();
    assertEquals(StringParser.StringMode.GREEDY_FLAG_YIELDING, stringMode);
  }

  @Test
  void shouldTakeTheCodecFlagOnEveryWallOfMaps() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    for (final String syntax : List.of(VIDEO_SYNTAX, IMAGE_SYNTAX, BROWSER_SYNTAX, VNC_SYNTAX)) {
      final Command<CommandSender> command = this.commands.getCommand(syntax);
      final ArgumentParser<CommandSender, ?> flagsParser = command.components().getLast().parser();
      final CommandFlagParser<?> flags = assertInstanceOf(CommandFlagParser.class, flagsParser, syntax);

      final List<String> names = flags.flags().stream().map(CommandFlag::name).toList();

      final String name = names.getFirst();
      assertEquals("codec", name, syntax);

      final CommandComponent<?> codec = Objects.requireNonNull(flags.flags().iterator().next().commandComponent());
      final ArgumentParser<?, ?> codecParser = codec.parser();
      final EnumParser<?, ?> values = assertInstanceOf(EnumParser.class, codecParser, syntax);

      final Set<? extends Enum<?>> copy = Set.copyOf(values.acceptedValues());
      final Set<MapCodec> expected = Set.of(MapCodec.DITHER, MapCodec.MCV2);
      assertEquals(expected, copy, syntax);
    }
  }

  @Test
  void shouldTakeASeekPositionWithColonsUnquoted() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final ArgumentParser<CommandSender, ?> positionParser = this.parserOf("mcav video seek position", "position");
    final StringParser<?> position = assertInstanceOf(StringParser.class, positionParser);

    final StringParser.StringMode stringMode = position.stringMode();
    assertEquals(StringParser.StringMode.GREEDY, stringMode, "a single word cannot hold the colon of 1:30");
  }

  @Test
  void shouldTakeQuotedFiltersOnEveryVideoAndImageCommand() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final List<String> media = this.commands
      .getSyntaxes()
      .stream()
      .filter(syntax -> (syntax.startsWith("mcav video ") || syntax.startsWith("mcav image ")) && syntax.contains(" mrl"))
      .toList();

    final int size = media.size();
    assertEquals(11, size, String.valueOf(media));

    for (final String syntax : media) {
      final Command<CommandSender> command = this.commands.getCommand(syntax);
      final ArgumentParser<CommandSender, ?> flagsParser = command.components().getLast().parser();
      final CommandFlagParser<?> flags = assertInstanceOf(CommandFlagParser.class, flagsParser, syntax);

      final CommandFlag<?> filters = flags
        .flags()
        .stream()
        .filter(flag -> flag.name().equals("filters"))
        .findFirst()
        .orElseThrow();
      final ArgumentParser<?, ?> filterParser = Objects.requireNonNull(filters.commandComponent()).parser();
      final StringParser<?> parser = assertInstanceOf(StringParser.class, filterParser, syntax);

      final StringParser.StringMode stringMode = parser.stringMode();
      assertEquals(StringParser.StringMode.QUOTED, stringMode, syntax);
    }
  }

  @Test
  void shouldLetTheBrowserAndTheMachineChooseTheirAudioOutputLikeTheVideos() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String video = VIDEO_SYNTAX;
    final String browser = BROWSER_SYNTAX;
    final String vm = VM_SYNTAX;
    final ArgumentParser<CommandSender, ?> videoAudio = this.parserOf(video, "audioType");
    for (final String syntax : List.of(browser, vm)) {
      final ArgumentParser<CommandSender, ?> audio = this.parserOf(syntax, "audioType");
      final EnumParser<?, ?> outputs = assertInstanceOf(EnumParser.class, audio);

      final Collection<?> expected = assertInstanceOf(EnumParser.class, videoAudio).acceptedValues();

      final Collection<? extends Enum<?>> acceptedValues = outputs.acceptedValues();
      assertEquals(expected, acceptedValues, syntax);
    }
    final Component description = ((RichDescription) this.commands.getCommand(browser).commandDescription().description()).contents();

    final String text = Components.plain(description);
    assertEquals("Opens a web page on a wall of maps, with its sound in an audio output", text);
  }

  @Test
  void shouldDescribeTheCommandsInTheLanguageOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final String dump = this.describeInPlainText("mcav dump");
    assertEquals("Creates a dump of the current server information", dump);

    final Set<String> syntaxes = this.commands.getSyntaxes();
    for (final String syntax : syntaxes) {
      final String text = this.describeInPlainText(syntax);
      final boolean described = !text.isBlank() && !text.startsWith("mcav.");
      assertTrue(described, "%s: %s".formatted(syntax, text));
    }
  }

  private String describeInPlainText(final String syntax) {
    final Command<CommandSender> command = this.commands.getCommand(syntax);
    final CommandDescription commandDescription = command.commandDescription();
    final Description description = commandDescription.description();
    final RichDescription rich = assertInstanceOf(RichDescription.class, description, syntax);
    final Component contents = rich.contents();
    return Components.plain(contents);
  }

  @Test
  void shouldRunTheParsedCommands() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class);
    when(this.videoManager.getPlayer()).thenReturn(Optional.of(player));
    final CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission(anyString())).thenReturn(true);
    final CommandExecutor<CommandSender> executor = this.commands.commandExecutor();
    final CompletableFuture<CommandResult<CommandSender>> execution = executor.executeCommand(sender, "mcav video pause");
    execution.join();
    verify(player).pause();

    final List<Component> messages = Components.received(sender);
    final Component paused = Message.PAUSE_PLAYER.build();
    final List<Component> expectedMessages = List.of(paused);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void shouldShutEveryFeatureDown() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      handler.shutdownCommands();
      handlers.verify(() -> HandlerList.unregisterAll(any(BrowserCommand.class)));
      handlers.verify(() -> HandlerList.unregisterAll(any(VirtualizeCommand.class)));
    }
  }

  @Test
  void shouldRollBackAllFeaturesWhenRegistrationFails() {
    final IllegalStateException failure = new IllegalStateException("listener registration failed!");
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        this.failFeatureRegistration(browser, failure)
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

  private void failFeatureRegistration(final BrowserCommand browser, final Throwable failure) {
    Mockito.doThrow(failure).when(browser).registerFeature(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void shouldKeepTheRegistrationFailurePrimaryWhenRollbackAlsoFails(final boolean sameFailure) {
    final IllegalStateException primary = new IllegalStateException(REGISTRATION_FAILURE_MESSAGE);
    final RuntimeException cleanup = sameFailure ? primary : new IllegalArgumentException(BROWSER_CLEANUP_FAILURE_MESSAGE);
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, context) ->
        this.failRegistrationAndShutdown(browser, primary, cleanup)
      );
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

  private void failRegistrationAndShutdown(final BrowserCommand browser, final IllegalStateException primary, final Throwable cleanup) {
    Mockito.doThrow(primary).when(browser).registerFeature(any());
    Mockito.doThrow(cleanup).when(browser).shutdown();
  }

  @Test
  void shouldShutLaterFeaturesDownEvenWhenAnEarlierFeatureFails() {
    final IllegalStateException failure = new IllegalStateException(BROWSER_CLEANUP_FAILURE_MESSAGE);
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        this.failShutdown(browser, failure)
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

  private void failShutdown(final BrowserCommand browser, final IllegalStateException failure) {
    Mockito.doThrow(failure).when(browser).shutdown();
  }

  @Test
  void shouldRenderDescriptionsWithTheMessagesOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    final RichDescription description = handler.describe("mcav.command.video.pause.info");
    final Component contents = description.contents();
    final String text = Components.plain(contents);
    assertEquals("Pauses the current video player", text);

    final RichDescription none = handler.describe("");
    final boolean empty = none.isEmpty();
    assertTrue(empty);
    assertThrows(MissingResourceException.class, () -> handler.describe("mcav.unknown"));
  }

  @Test
  void shouldRefuseANullPlugin() {
    assertThrows(NullPointerException.class, () -> new AnnotationParserHandler(null));
  }

  @Test
  void shouldPropagateFatalRegistrationFailureWithoutAttemptingRollback() {
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        this.failFeatureRegistration(browser, fatal)
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
  void shouldPropagateFatalCleanupFailureDuringRollback() {
    final IllegalStateException primary = new IllegalStateException(REGISTRATION_FAILURE_MESSAGE);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    try (
      final MockedConstruction<BrowserCommand> browsers = Mockito.mockConstruction(BrowserCommand.class, (browser, _) ->
        this.failRegistrationAndShutdown(browser, primary, fatal)
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
      verify(browser).shutdown();
      verify(machine, never()).shutdown();
    }
  }
}
