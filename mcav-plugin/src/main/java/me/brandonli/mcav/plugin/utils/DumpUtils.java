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
package me.brandonli.mcav.plugin.utils;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.InetAddresses;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.plugin.PluginException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Collects diagnostic information for bug reports and uploads it to a paste site.
 *
 * <p>The dump is public, so it contains no environment variables, and the values of JVM properties and JVM arguments
 * whose names suggest secrets, such as {@code -Dbot.token=...}, are redacted. Only the end of the server log is
 * included, and its lines lose IP addresses, the arguments of other plugins' commands, and the credentials, queries and
 * fragments of web addresses. Redaction is heuristic: unlabeled secrets and application-specific credential formats may
 * still appear in diagnostic text.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class DumpUtils {

  private static final String PASTE_SITE = "https://paste.helpch.at/";
  private static final String DOCUMENTS_PATH = "documents/";
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);

  private static final Path LOG_FILE = Path.of("logs", "latest.log");
  private static final int MAXIMUM_LOG_LINES = 2_000;

  private static final List<String> SECRET_WORDS = List.of("token", "password", "passwd", "secret", "key", "credential", "auth");
  private static final String REDACTED = "<redacted>";
  private static final String REDACTED_ADDRESS = "<redacted-address>";

  private static final Pattern WORD = Pattern.compile("\\S+");
  private static final Pattern IPV4_ADDRESS = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b(?::\\d{1,5})?");
  private static final Pattern IPV6_ADDRESS = Pattern.compile("/\\[[0-9A-Fa-f:.]+(?:%[^\\]\\s]+)?](?::\\d{1,5})?");
  private static final Pattern IPV6_LITERAL = Pattern.compile(
    "(?<![\\w:])((?:[0-9A-Fa-f]{1,4}::?){1,7}[0-9A-Fa-f]{1,4})(?!\\w)(?:%[\\w.-]+)?"
  );
  private static final Pattern ISSUED_COMMAND = Pattern.compile("(issued server command: /)([^\\s]+)(\\s.*)?$");
  private static final Pattern WEB_ADDRESS = Pattern.compile("\\b([A-Za-z][A-Za-z0-9+.-]*://)([^\\s/?#]*@)?([^\\s?#]*)([?#]\\S*)?");

  private static final String OWN_COMMAND_NAME = "mcav";

  private static final long MEGABYTE = 1024L * 1024L;

  private static final String CONTENT_TYPE_HEADER = "Content-Type";

  private static final String PLAIN_TEXT_CONTENT_TYPE = "text/plain";

  private static final String LABEL_SEPARATOR = ": ";

  private static final char LINE_SEPARATOR = '\n';

  private static final String SECTION_PREFIX = "\n=== ";
  private static final String SECTION_SUFFIX = " ===\n";

  private static final String PROPERTY_SEPARATOR = "=";

  private static final String OPTION_PREFIX = "-";

  private static final String WORD_SEPARATOR = " ";

  private static final int SCHEME_GROUP = 1;
  private static final int USER_GROUP = 2;
  private static final int HOST_GROUP = 3;
  private static final int QUERY_OR_FRAGMENT_GROUP = 4;

  private static final String USER_SEPARATOR = "@";

  private static final String REDACTED_IPV6_ADDRESS = "/<redacted-address>";

  private static final int IPV6_LITERAL_ADDRESS_GROUP = 1;

  private static final int COMMAND_NAME_GROUP = 2;
  private static final int COMMAND_ARGUMENTS_GROUP = 3;

  private static final String STACK_FRAME_PREFIX = "\tat ";

  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_VERSION_PROPERTY = "os.version";
  private static final String OS_ARCHITECTURE_PROPERTY = "os.arch";
  private static final String JAVA_VERSION_PROPERTY = "java.version";
  private static final String JAVA_VENDOR_PROPERTY = "java.vendor";

  private DumpUtils() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Collects the dump and uploads it.
   *
   * @return the URL of the uploaded dump
   * @throws PluginException if the upload fails
   * @since 1.0.0
   */
  public static String createAndUploadDump() {
    return createAndUploadDump(HTTP_CLIENT, PASTE_SITE, LOG_FILE);
  }

  @VisibleForTesting
  static String createAndUploadDump(final HttpClient client, final String pasteSite, final Path logFile) {
    final String dump = createDumpContents(logFile);
    try {
      final String endpointUrl = "%s%s".formatted(pasteSite, DOCUMENTS_PATH);
      final URI endpoint = URI.create(endpointUrl);
      final String response = uploadDump(client, endpoint, dump);
      final String documentKey = parseDocumentKey(response);
      final String url = "%s%s".formatted(pasteSite, documentKey);
      return url;
    } catch (final IOException exception) {
      final String message = "The dump could not be uploaded!";
      throw new PluginException(message, exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Interrupted while uploading the dump!";
      throw new PluginException(message, exception);
    }
  }

  private static String parseDocumentKey(final String response) {
    final JsonElement element = parseJson(response);
    if (element.isJsonObject()) {
      final JsonObject document = element.getAsJsonObject();
      final JsonElement key = document.get("key");
      if (key != null && key.isJsonPrimitive()) {
        return key.getAsString();
      }
    }

    final String message = "Paste site answer %s has no document key!".formatted(response);
    throw new PluginException(message);
  }

  private static JsonElement parseJson(final String response) {
    try {
      return JsonParser.parseString(response);
    } catch (final JsonParseException exception) {
      final String message = "Paste site answer %s is not JSON!".formatted(response);
      throw new PluginException(message, exception);
    }
  }

  private static String uploadDump(final HttpClient client, final URI endpoint, final String dump)
    throws IOException, InterruptedException {
    final HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString(dump);
    final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);
    builder.header(CONTENT_TYPE_HEADER, PLAIN_TEXT_CONTENT_TYPE);
    builder.timeout(UPLOAD_TIMEOUT);
    builder.POST(body);
    final HttpRequest request = builder.build();
    final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
    final HttpResponse<String> response = client.send(request, handler);
    final int status = response.statusCode();
    if (status != HttpURLConnection.HTTP_OK) {
      final String message = "The paste site answered with HTTP status %s, which is not a success!".formatted(status);
      throw new IOException(message);
    }

    return response.body();
  }

  @VisibleForTesting
  static String createDumpContents(final Path logFile) {
    final StringBuilder dump = new StringBuilder();
    appendSystem(dump);
    appendProperties(dump);
    appendThreads(dump);
    appendLog(dump, logFile);
    return dump.toString();
  }

  private static void appendSystem(final StringBuilder dump) {
    appendSection(dump, "System");
    appendOperatingSystem(dump);
    appendMemory(dump);
    appendJvm(dump);
  }

  private static void appendSection(final StringBuilder dump, final String title) {
    dump.append(SECTION_PREFIX);
    dump.append(title);
    dump.append(SECTION_SUFFIX);
  }

  private static void appendOperatingSystem(final StringBuilder dump) {
    final String osName = System.getProperty(OS_NAME_PROPERTY);
    final String osVersion = System.getProperty(OS_VERSION_PROPERTY);
    final String architecture = System.getProperty(OS_ARCHITECTURE_PROPERTY);
    final String javaVersion = System.getProperty(JAVA_VERSION_PROPERTY);
    final String javaVendor = System.getProperty(JAVA_VENDOR_PROPERTY);
    appendLine(dump, "OS", osName);
    appendLine(dump, "OS Version", osVersion);
    appendLine(dump, "Architecture", architecture);
    appendLine(dump, "Java Version", javaVersion);
    appendLine(dump, "Java Vendor", javaVendor);
  }

  private static void appendLine(final StringBuilder dump, final String name, final @Nullable Object value) {
    dump.append(name);
    dump.append(LABEL_SEPARATOR);
    dump.append(value);
    dump.append(LINE_SEPARATOR);
  }

  private static void appendMemory(final StringBuilder dump) {
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final long freeMemory = runtime.freeMemory();
    final long totalMemory = runtime.totalMemory();
    final long maximumMemory = runtime.maxMemory();
    appendLine(dump, "Processors", processors);
    appendLine(dump, "Free Memory (MB)", freeMemory / MEGABYTE);
    appendLine(dump, "Total Memory (MB)", totalMemory / MEGABYTE);
    appendLine(dump, "Max Memory (MB)", maximumMemory / MEGABYTE);
  }

  private static void appendJvm(final StringBuilder dump) {
    final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    final long uptime = runtimeBean.getUptime();
    final List<String> arguments = runtimeBean.getInputArguments();
    final List<String> redactedArguments = redactArguments(arguments);
    appendLine(dump, "Uptime (ms)", uptime);
    appendLine(dump, "JVM Arguments", redactedArguments);
  }

  // everything after the first equals sign is redacted
  @VisibleForTesting
  static List<String> redactArguments(final List<String> arguments) {
    return arguments.stream().map(DumpUtils::redactArgument).toList();
  }

  private static String redactArgument(final String argument) {
    final int lastEquals = argument.lastIndexOf(PROPERTY_SEPARATOR);
    if (lastEquals < 0) {
      return argument;
    }

    final String names = argument.substring(0, lastEquals);
    final boolean secret = isSecret(names);
    if (!secret) {
      return argument;
    }

    final int firstEquals = argument.indexOf(PROPERTY_SEPARATOR);
    final String name = argument.substring(0, firstEquals);
    final String redacted = "%s%s%s".formatted(name, PROPERTY_SEPARATOR, REDACTED);
    return redacted;
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

  // sun.java.command repeats the command line, so tokens such as --api-token=abc hide under harmless names
  private static String redactSecretsInside(final String value) {
    final Matcher words = WORD.matcher(value);
    while (words.find()) {
      final String word = words.group();
      final String redacted = redactArgument(word);
      if (!redacted.equals(word)) {
        final int start = words.start();
        final String prefix = value.substring(0, start);
        final String safe = "%s%s".formatted(prefix, redacted);
        return safe;
      }
      if (word.startsWith(OPTION_PREFIX) && isSecret(word)) {
        final int end = words.end();
        final String prefix = value.substring(0, end);
        final String safe = "%s%s%s".formatted(prefix, WORD_SEPARATOR, REDACTED);
        return safe;
      }
    }

    return value;
  }

  // the dump is public: other plugins' command arguments and URL credentials, queries and fragments are redacted
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
      final String user = address.group(USER_GROUP);
      final String queryOrFragment = address.group(QUERY_OR_FRAGMENT_GROUP);
      final String userPart = user == null ? "" : "%s%s".formatted(REDACTED, USER_SEPARATOR);
      final String tail = queryOrFragment == null ? "" : "%s%s".formatted(queryOrFragment.charAt(0), REDACTED);
      final String scheme = address.group(SCHEME_GROUP);
      final String host = address.group(HOST_GROUP);
      final String replacement = "%s%s%s%s".formatted(scheme, userPart, host, tail);
      final String quotedReplacement = Matcher.quoteReplacement(replacement);
      address.appendReplacement(redacted, quotedReplacement);
    }

    address.appendTail(redacted);
    return redacted.toString();
  }

  private static String redactAddresses(final String line) {
    final Matcher ipv6Addresses = IPV6_ADDRESS.matcher(line);
    final String withoutIpv6Addresses = ipv6Addresses.replaceAll(REDACTED_IPV6_ADDRESS);
    final Matcher ipv4Addresses = IPV4_ADDRESS.matcher(withoutIpv6Addresses);
    final String withoutIpv4Addresses = ipv4Addresses.replaceAll(REDACTED_ADDRESS);
    return redactIpv6Literals(withoutIpv4Addresses);
  }

  // a timestamp such as 12:34:56 matches the pattern too, so only a valid address is replaced
  private static String redactIpv6Literals(final String line) {
    final Matcher literals = IPV6_LITERAL.matcher(line);
    final StringBuilder redacted = new StringBuilder(line.length());
    while (literals.find()) {
      final String text = literals.group(IPV6_LITERAL_ADDRESS_GROUP);
      final String address = requireNonNull(text);
      final boolean valid = InetAddresses.isInetAddress(address);
      final String literal = literals.group();
      final String replacement = valid ? REDACTED_ADDRESS : literal;
      final String quotedReplacement = Matcher.quoteReplacement(replacement);
      literals.appendReplacement(redacted, quotedReplacement);
    }

    literals.appendTail(redacted);
    return redacted.toString();
  }

  private static String redactOtherCommandArguments(final String line) {
    final Matcher matcher = ISSUED_COMMAND.matcher(line);
    final boolean issued = matcher.find();
    if (!issued) {
      return line;
    }

    final String name = matcher.group(COMMAND_NAME_GROUP);
    final String arguments = matcher.group(COMMAND_ARGUMENTS_GROUP);
    final String commandName = requireNonNull(name);
    final boolean own = commandName.equalsIgnoreCase(OWN_COMMAND_NAME);
    if (own || arguments == null || arguments.isBlank()) {
      return line;
    }

    final int argumentsStart = matcher.start(COMMAND_ARGUMENTS_GROUP);
    final String prefix = line.substring(0, argumentsStart);
    final String redacted = "%s%s%s".formatted(prefix, WORD_SEPARATOR, REDACTED);
    return redacted;
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
      final String description = "%s (%s)".formatted(threadName, state);
      appendLine(dump, "Thread", description);
      final StackTraceElement[] elements = entry.getValue();
      for (final StackTraceElement element : elements) {
        dump.append(STACK_FRAME_PREFIX);
        dump.append(element);
        dump.append(LINE_SEPARATOR);
      }
    }
  }

  private static void appendLog(final StringBuilder dump, final Path logFile) {
    appendSection(dump, "Latest Log");
    final boolean exists = Files.isRegularFile(logFile);
    if (!exists) {
      dump.append("No log found");
      dump.append(LINE_SEPARATOR);
      return;
    }

    try (final BufferedReader reader = Files.newBufferedReader(logFile)) {
      final Deque<String> tail = readTail(reader);
      for (final String retained : tail) {
        final String safe = redactLogLine(retained);
        dump.append(safe);
        dump.append(LINE_SEPARATOR);
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      appendLine(dump, "Failed to read the log", message);
    }
  }

  private static Deque<String> readTail(final BufferedReader reader) throws IOException {
    final Deque<String> tail = new ArrayDeque<>();
    String line;
    while ((line = reader.readLine()) != null) {
      tail.addLast(line);
      if (tail.size() > MAXIMUM_LOG_LINES) {
        tail.removeFirst();
      }
    }

    return tail;
  }
}
