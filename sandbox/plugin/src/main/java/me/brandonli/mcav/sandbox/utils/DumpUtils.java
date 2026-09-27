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
package me.brandonli.mcav.sandbox.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Collects diagnostic information for bug reports and uploads it to a paste site.
 *
 * <p>The dump is public, so it contains no environment variables, and the values of JVM properties and JVM
 * arguments whose names suggest secrets, such as {@code -Dbot.token=...}, are redacted. Only the end of the server
 * log is included. Redaction is heuristic: unlabelled secrets and application-specific credential formats may
 * still appear in diagnostic text.
 */
public final class DumpUtils {

  private static final String PASTE_SITE = "https://paste.helpch.at/";
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);
  private static final Path LOG_FILE = Path.of("logs", "latest.log");
  private static final int MAX_LOG_LINES = 2_000;
  private static final List<String> SECRET_WORDS = List.of("token", "password", "passwd", "secret", "key", "credential", "auth");
  private static final String REDACTED = "<redacted>";
  private static final String REDACTED_ADDRESS = "<redacted-address>";
  private static final Pattern WORD = Pattern.compile("\\S+");
  // the address of a player, as the server logs it when they join or leave, with or without its port
  private static final Pattern IPV4_ADDRESS = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b(?::\\d{1,5})?");
  // the bracketed form the server logs an IPv6 address in, such as /[::1]:25565, and never a time of day; a link-local
  // address carries its zone after a percent sign, such as /[fe80:0:0:0:0:0:0:1%eth0]:25565
  private static final Pattern IPV6_ADDRESS = Pattern.compile("/\\[[0-9A-Fa-f:.]+(?:%[^\\]\\s]+)?](?::\\d{1,5})?");
  // what a player typed after a command of another plugin, which may be their password
  private static final Pattern OTHER_COMMAND = Pattern.compile("(issued server command: /)([^\\s]+)(\\s.*)?$");
  // an address with a host, such as a page of the browser or a video: its user name and password (group 2) and its
  // query or fragment (group 4) often carry secrets, such as the signature of a link or the code of a login
  private static final Pattern WEB_ADDRESS = Pattern.compile("\\b([A-Za-z][A-Za-z0-9+.-]*://)([^\\s/?#]*@)?([^\\s?#]*)([?#]\\S*)?");
  private static final String OWN_COMMAND_PREFIX = "mcav";
  private static final long MEGABYTE = 1024L * 1024L;

  private DumpUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Collects the dump and uploads it.
   *
   * @return the URL of the uploaded dump
   * @throws IllegalStateException if the upload fails
   */
  public static String createAndUploadDump() {
    return createAndUploadDump(HTTP_CLIENT, PASTE_SITE, LOG_FILE);
  }

  /**
   * Collects the dump and uploads it to a paste site that speaks the hastebin protocol.
   *
   * @param client    the HTTP client
   * @param pasteSite the URL of the paste site, ending with a slash
   * @param logFile   the server log whose end is included
   * @return the URL of the uploaded dump
   * @throws IllegalStateException if the upload fails
   */
  @VisibleForTesting
  static String createAndUploadDump(final HttpClient client, final String pasteSite, final Path logFile) {
    final String dump = createDumpContents(logFile);
    try {
      final URI endpoint = URI.create(pasteSite + "documents/");
      final String response = uploadDump(client, endpoint, dump);
      final String documentKey = parseDocumentKey(response);
      return pasteSite + documentKey;
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new IllegalStateException("Failed to upload the dump: " + message, exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IllegalStateException("Interrupted while uploading the dump", exception);
    }
  }

  /**
   * Reads the key of the uploaded document from the answer of the paste site, such as {@code {"key":"abc"}}.
   */
  private static String parseDocumentKey(final String response) {
    final JsonElement element = parseJson(response);
    if (element.isJsonObject()) {
      final JsonObject document = element.getAsJsonObject();
      final JsonElement key = document.get("key");
      if (key != null && key.isJsonPrimitive()) {
        return key.getAsString();
      }
    }
    throw new IllegalStateException("The paste site did not answer with a document key: " + response);
  }

  private static JsonElement parseJson(final String response) {
    try {
      return JsonParser.parseString(response);
    } catch (final JsonParseException exception) {
      throw new IllegalStateException("The paste site did not answer with JSON: " + response, exception);
    }
  }

  private static String uploadDump(final HttpClient client, final URI endpoint, final String dump)
    throws IOException, InterruptedException {
    final HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString(dump);
    final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);
    builder.header("Content-Type", "text/plain");
    builder.timeout(UPLOAD_TIMEOUT);
    builder.POST(body);
    final HttpRequest request = builder.build();
    final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
    final HttpResponse<String> response = client.send(request, handler);
    final int status = response.statusCode();
    if (status != 200) {
      throw new IOException("The paste site answered with HTTP " + status);
    }
    return response.body();
  }

  /**
   * Collects the dump: the system, the JVM properties with secrets redacted, the stack traces of every thread,
   * and the end of the server log.
   *
   * @param logFile the server log
   * @return the dump
   */
  @VisibleForTesting
  static String createDumpContents(final Path logFile) {
    final StringBuilder dump = new StringBuilder();
    appendSystem(dump);
    appendProperties(dump);
    appendThreads(dump);
    appendLog(dump, logFile);
    return dump.toString();
  }

  private static void appendLine(final StringBuilder dump, final String name, final @Nullable Object value) {
    dump.append(name);
    dump.append(": ");
    dump.append(value);
    dump.append('\n');
  }

  private static void appendSection(final StringBuilder dump, final String title) {
    dump.append("\n=== ");
    dump.append(title);
    dump.append(" ===\n");
  }

  private static void appendSystem(final StringBuilder dump) {
    appendSection(dump, "System");
    appendOperatingSystem(dump);
    appendMemory(dump);
    appendJvm(dump);
  }

  private static void appendOperatingSystem(final StringBuilder dump) {
    final String osName = System.getProperty("os.name");
    final String osVersion = System.getProperty("os.version");
    final String architecture = System.getProperty("os.arch");
    final String javaVersion = System.getProperty("java.version");
    final String javaVendor = System.getProperty("java.vendor");
    appendLine(dump, "OS", osName);
    appendLine(dump, "OS Version", osVersion);
    appendLine(dump, "Architecture", architecture);
    appendLine(dump, "Java Version", javaVersion);
    appendLine(dump, "Java Vendor", javaVendor);
  }

  private static void appendMemory(final StringBuilder dump) {
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final long freeMemory = runtime.freeMemory();
    final long totalMemory = runtime.totalMemory();
    final long maxMemory = runtime.maxMemory();
    appendLine(dump, "Processors", processors);
    appendLine(dump, "Free Memory (MB)", freeMemory / MEGABYTE);
    appendLine(dump, "Total Memory (MB)", totalMemory / MEGABYTE);
    appendLine(dump, "Max Memory (MB)", maxMemory / MEGABYTE);
  }

  private static void appendJvm(final StringBuilder dump) {
    final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    final long uptime = runtimeBean.getUptime();
    final List<String> arguments = runtimeBean.getInputArguments();
    final List<String> redactedArguments = redactArguments(arguments);
    appendLine(dump, "Uptime (ms)", uptime);
    appendLine(dump, "JVM Arguments", redactedArguments);
  }

  /**
   * Redacts the values of JVM arguments that look secret, such as {@code -Dbot.token=abc} or
   * {@code -Djavax.net.ssl.keyStorePassword=abc}. Everything after the first equals sign of an argument is
   * redacted when the text before its last equals sign looks secret, so a secret among the options of an agent,
   * such as {@code -javaagent:agent.jar=token=abc}, is redacted as well.
   *
   * @param arguments the arguments
   * @return the arguments with secrets redacted, in the same order
   */
  @VisibleForTesting
  static List<String> redactArguments(final List<String> arguments) {
    final List<String> redacted = new ArrayList<>();
    for (final String argument : arguments) {
      final String safe = redactArgument(argument);
      redacted.add(safe);
    }
    return redacted;
  }

  private static String redactArgument(final String argument) {
    final int lastEquals = argument.lastIndexOf('=');
    if (lastEquals < 0) {
      return argument;
    }
    final String names = argument.substring(0, lastEquals);
    final boolean secret = isSecret(names);
    if (!secret) {
      return argument;
    }
    final int firstEquals = argument.indexOf('=');
    final String name = argument.substring(0, firstEquals);
    return name + "=" + REDACTED;
  }

  private static void appendProperties(final StringBuilder dump) {
    appendSection(dump, "JVM Properties");
    final Properties properties = System.getProperties();
    final Map<String, String> sorted = new TreeMap<>();
    final Set<String> names = properties.stringPropertyNames();
    for (final String name : names) {
      final String value = properties.getProperty(name, "");
      final boolean secret = isSecret(name);
      final String safe = secret ? REDACTED : redactSecretsInside(value);
      sorted.put(name, safe);
    }
    for (final Map.Entry<String, String> entry : sorted.entrySet()) {
      final String name = entry.getKey();
      final String value = entry.getValue();
      appendLine(dump, name, value);
    }
  }

  /**
   * Redacts the secrets a property value carries even though the name of the property does not look secret.
   *
   * <p>The dump is uploaded to a public paste site, so a value that repeats the command line must not pass through
   * untouched. {@code sun.java.command} holds the whole command line of the program, so a server started with
   * {@code --api-token=abc} publishes that token under a property name in which no secret word appears. Every
   * value is scanned for assignments and secret command options. From the first secret onward the remainder is
   * omitted, including quoted or space-separated values. This intentionally sacrifices trailing diagnostics
   * because the JVM does not retain shell quoting reliably. The same heuristic applies to log lines; it cannot
   * identify unlabelled secrets or every application-specific credential format.
   *
   * @param value the property value or log line
   * @return the text before the first secret followed by a redaction marker
   */
  private static String redactSecretsInside(final String value) {
    final Matcher words = WORD.matcher(value);
    while (words.find()) {
      final String word = words.group();
      final String redacted = redactArgument(word);
      if (!redacted.equals(word)) {
        final String prefix = value.substring(0, words.start());
        return prefix + redacted;
      }
      if (word.startsWith("-") && isSecret(word)) {
        final String prefix = value.substring(0, words.end());
        return prefix + " " + REDACTED;
      }
    }
    return value;
  }

  /**
   * Redacts a line of the server log before it is published. The dump goes to a paste site anyone can read, so the
   * addresses of players and what they typed after the commands of other plugins never belong in it; the arguments of
   * the commands of this plugin stay, because they are what a bug report is about, but the user name and password, the
   * query and the fragment of every web address in the line are redacted, as they often carry secrets.
   *
   * @param line the line of the log
   * @return the line as it may be published
   */
  @VisibleForTesting
  static String redactLogLine(final String line) {
    final String withoutSecrets = redactSecretsInside(line);
    final String withoutAddressSecrets = redactWebAddressSecrets(withoutSecrets);
    final String withoutArguments = redactOtherCommandArguments(withoutAddressSecrets);
    return redactAddresses(withoutArguments);
  }

  private static String redactWebAddressSecrets(final String line) {
    final Matcher address = WEB_ADDRESS.matcher(line);
    final StringBuilder redacted = new StringBuilder(line.length());
    while (address.find()) {
      final String user = address.group(2);
      final String queryOrFragment = address.group(4);
      final String userPart = user == null ? "" : REDACTED + "@";
      final String tail = queryOrFragment == null ? "" : queryOrFragment.charAt(0) + REDACTED;
      final String replacement = address.group(1) + userPart + address.group(3) + tail;
      address.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
    }
    address.appendTail(redacted);
    return redacted.toString();
  }

  private static String redactAddresses(final String line) {
    final Matcher sixth = IPV6_ADDRESS.matcher(line);
    final String withoutSixth = sixth.replaceAll("/" + REDACTED_ADDRESS);
    final Matcher fourth = IPV4_ADDRESS.matcher(withoutSixth);
    return fourth.replaceAll(REDACTED_ADDRESS);
  }

  private static String redactOtherCommandArguments(final String line) {
    final Matcher matcher = OTHER_COMMAND.matcher(line);
    final boolean command = matcher.find();
    if (!command) {
      return line;
    }
    final String name = matcher.group(2);
    final String arguments = matcher.group(3);
    // group 2 always takes part in a match, group 3 only when the command has arguments
    final String commandName = Objects.requireNonNull(name);
    final boolean own = commandName.equalsIgnoreCase(OWN_COMMAND_PREFIX);
    if (own || arguments == null || arguments.isBlank()) {
      return line;
    }
    final String prefix = line.substring(0, matcher.start(3));
    return prefix + " " + REDACTED;
  }

  private static boolean isSecret(final String name) {
    final String lower = name.toLowerCase(Locale.ROOT);
    for (final String word : SECRET_WORDS) {
      if (lower.contains(word)) {
        return true;
      }
    }
    return false;
  }

  private static void appendThreads(final StringBuilder dump) {
    appendSection(dump, "Threads");
    final Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
    for (final Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
      final Thread thread = entry.getKey();
      final String threadName = thread.getName();
      final Thread.State state = thread.getState();
      appendLine(dump, "Thread", threadName + " (" + state + ")");
      final StackTraceElement[] elements = entry.getValue();
      for (final StackTraceElement element : elements) {
        dump.append("\tat ");
        dump.append(element);
        dump.append('\n');
      }
    }
  }

  private static void appendLog(final StringBuilder dump, final Path logFile) {
    appendSection(dump, "Latest Log");
    final boolean exists = Files.isRegularFile(logFile);
    if (!exists) {
      dump.append("No log found\n");
      return;
    }
    try (final BufferedReader reader = Files.newBufferedReader(logFile)) {
      final Deque<String> tail = new ArrayDeque<>();
      String line;
      while ((line = reader.readLine()) != null) {
        tail.addLast(line);
        if (tail.size() > MAX_LOG_LINES) {
          tail.removeFirst();
        }
      }
      for (final String retained : tail) {
        final String safe = redactLogLine(retained);
        dump.append(safe);
        dump.append('\n');
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      appendLine(dump, "Failed to read the log", message);
    }
  }
}
