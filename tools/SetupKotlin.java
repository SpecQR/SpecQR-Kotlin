import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Download an official, pinned, SHA-256-verified compiler; it is a build-only tool. */
class SetupKotlin {
  static final Map<String,String> HASHES = Map.of(
      "2.2.21", "a623871f1cd9c938946948b70ef9170879f0758043885bbd30c32f024e511714",
      "2.3.21", "a8cfc1d62cd4d0de4d04f42575e40135bd620588c17d568a20eb9c7c259af14f",
      "2.4.20", "59e9ca74c7904ef2c122b12114937673ccce68de820a663f0ed66ccf8799e0b7");
  public static void main(String[] args) throws Exception {
    String version = args.length == 0 ? "2.4.20" : args[0];
    String expected = HASHES.get(version);
    if (expected == null) throw new IllegalArgumentException("Supported compiler versions: " + HASHES.keySet());
    Path base = Path.of(".tools", "kotlin-" + version).toAbsolutePath().normalize();
    Path marker = base.resolve("verified-sha256.txt");
    if (Files.isRegularFile(marker) && Files.readString(marker).trim().equals(expected)
        && Files.isRegularFile(base.resolve("kotlinc/lib/kotlin-compiler.jar"))) {
      System.out.println(base.resolve("kotlinc")); return;
    }
    Files.createDirectories(base);
    URI uri = URI.create("https://github.com/JetBrains/kotlin/releases/download/v" + version + "/kotlin-compiler-" + version + ".zip");
    var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
    var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) throw new IOException("Official download returned HTTP " + response.statusCode());
    byte[] bytes;
    try (InputStream input = response.body()) {
      bytes = input.readNBytes(160 * 1024 * 1024 + 1);
      if (bytes.length > 160 * 1024 * 1024) throw new IOException("Compiler archive exceeds size budget");
    }
    String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if (!actual.equals(expected)) throw new SecurityException("Official compiler checksum mismatch");
    long expanded = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path target = base.resolve(entry.getName()).normalize();
        if (!target.startsWith(base) || !entry.getName().startsWith("kotlinc/")) throw new IOException("Invalid compiler archive path");
        if (entry.isDirectory()) { Files.createDirectories(target); continue; }
        Files.createDirectories(target.getParent());
        try (OutputStream output = Files.newOutputStream(target)) {
          byte[] buffer = new byte[65536]; int n;
          while ((n = zip.read(buffer)) != -1) {
            expanded += n;
            if (expanded > 400L * 1024 * 1024) throw new IOException("Compiler expansion exceeds size budget");
            output.write(buffer, 0, n);
          }
        }
        if (entry.getName().startsWith("kotlinc/bin/") && !System.getProperty("os.name").startsWith("Windows")
            && !target.toFile().setExecutable(true, false)) throw new IOException("Cannot mark compiler launcher executable");
      }
    }
    Files.writeString(marker, expected + "\n");
    System.out.println(base.resolve("kotlinc"));
  }
}
