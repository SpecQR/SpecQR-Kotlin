import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** No build framework: KOTLIN_HOME=/path/to/kotlinc java tools/Build.java [test]. */
class Build {
  static final Path ROOT = Path.of("").toAbsolutePath();
  static final String VERSION = "0.1.0-rc.1";
  static final String SEP = File.pathSeparator;
  static Path kotlinHome() {
    String home = System.getenv("KOTLIN_HOME");
    if (home == null || home.isBlank()) throw new IllegalStateException("Set KOTLIN_HOME to an official Kotlin compiler distribution (the directory containing lib/).");
    Path result = Path.of(home).toAbsolutePath();
    if (!Files.isRegularFile(result.resolve("lib/kotlin-compiler.jar")) || !Files.isRegularFile(result.resolve("lib/kotlin-stdlib.jar")))
      throw new IllegalStateException("KOTLIN_HOME must contain lib/kotlin-compiler.jar and lib/kotlin-stdlib.jar");
    return result;
  }
  static String java() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(); }
  static void run(List<String> args) throws Exception {
    int result = new ProcessBuilder(args).inheritIO().start().waitFor();
    if (result != 0) throw new IllegalStateException("Tool failed with exit " + result);
  }
  public static void main(String[] args) throws Exception {
    if (!Files.isDirectory(ROOT.resolve("src/main/kotlin"))) throw new IllegalStateException("Run from the repository root");
    Path home = kotlinHome(), stdlib = home.resolve("lib/kotlin-stdlib.jar");
    Path classes = ROOT.resolve("build/classes"), tests = ROOT.resolve("build/tests"), dist = ROOT.resolve("dist");
    clear(classes); Files.createDirectories(classes); Files.createDirectories(dist);
    compileKotlin(ROOT.resolve("src/main/kotlin"), classes, null, home);
    jar(classes, dist.resolve("specqr-kotlin-" + VERSION + ".jar"), true);
    jar(ROOT.resolve("src/main/kotlin"), dist.resolve("specqr-kotlin-" + VERSION + "-sources.jar"), false);
    System.out.println("Built SpecQR Kotlin thin JARs (JVM 17, Kotlin language/API 2.2)");
    if (args.length > 0 && args[0].equals("test")) {
      clear(tests); Files.createDirectories(tests);
      compileJava(ROOT.resolve("src/test/java"), tests, classes + SEP + stdlib);
      run(List.of(java(), "-ea", "-cp", classes + SEP + tests + SEP + stdlib, "io.specqr.TestRunner"));
      if (Files.isDirectory(ROOT.resolve("src/test/kotlin"))) {
        Path kotlinTests = ROOT.resolve("build/kotlin-tests"); clear(kotlinTests); Files.createDirectories(kotlinTests);
        compileKotlin(ROOT.resolve("src/test/kotlin"), kotlinTests, classes + SEP + stdlib, home);
        run(List.of(java(), "-ea", "--limit-modules", "java.base", "-cp", classes + SEP + kotlinTests + SEP + stdlib, "io.specqr.KotlinTests"));
      }
    }
  }
  static void compileKotlin(Path source, Path out, String cp, Path home) throws Exception {
    List<String> args = new ArrayList<>(List.of(java(), "-Dfile.encoding=UTF-8", "-Xmx1g", "-cp", home.resolve("lib/*").toString(), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-kotlin-home", home.toString(), "-Xjdk-release=17", "-language-version", "2.2", "-api-version", "2.2", "-no-reflect", "-Werror", "-module-name", "specqr", "-d", out.toString()));
    if (cp != null) args.addAll(List.of("-classpath", cp));
    try (var paths = Files.walk(source)) { paths.filter(p -> p.toString().endsWith(".kt")).sorted().forEach(p -> args.add(p.toString())); }
    run(args);
  }
  static void compileJava(Path source, Path out, String cp) throws Exception {
    List<String> args = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", out.toString(), "-cp", cp));
    try (var paths = Files.walk(source)) { paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> args.add(p.toString())); }
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("A full JDK 17+ is required");
    if (compiler.run(null, System.out, System.err, args.toArray(String[]::new)) != 0) throw new IllegalStateException("Java test compilation failed");
  }
  static void clear(Path dir) throws IOException {
    if (Files.exists(dir)) try (var paths = Files.walk(dir)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
  }
  static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    ZipEntry entry = new ZipEntry(name); entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0)); entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length);
    CRC32 crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue()); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
  }
  static void jar(Path source, Path target, boolean main) throws IOException {
    try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
      if (main) entry(zip, "META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\nMain-Class: io.specqr.Main\r\nAutomatic-Module-Name: io.specqr\r\nImplementation-Version: " + VERSION + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      entry(zip, "META-INF/LICENSE", Files.readAllBytes(ROOT.resolve("LICENSE")));
      entry(zip, "META-INF/NOTICE", Files.readAllBytes(ROOT.resolve("NOTICE")));
      try (var paths = Files.walk(source)) {
        for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) entry(zip, source.relativize(path).toString().replace(File.separatorChar, '/'), Files.readAllBytes(path));
      }
    }
  }
}
