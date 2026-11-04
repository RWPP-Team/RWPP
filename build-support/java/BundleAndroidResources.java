import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** Builds an extension-owned resource APK and the matching library R classes. */
public final class BundleAndroidResources {
    private static final String GLOBAL = "io.github.rwpp.extension.resources";
    private record Library(String namespace, List<String> symbols) {}
    private static void run(List<String> args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(String.join(" ", args) + "\n" + output);
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[2]), generated = out.resolve("java");
        Files.createDirectories(generated);
        List<Library> libraries = new ArrayList<>();
        List<String> compiled = new ArrayList<>();
        int index = 0;
        for (String line : Files.readAllLines(Path.of(args[3]))) {
            if (line.isBlank()) continue;
            Path unpack = out.resolve("aar-" + index++);
            try (ZipFile zip = new ZipFile(line)) {
                ZipEntry manifest = zip.getEntry("AndroidManifest.xml"), symbols = zip.getEntry("R.txt");
                if (manifest == null || symbols == null) continue;
                String xml = new String(zip.getInputStream(manifest).readAllBytes(), StandardCharsets.UTF_8);
                Matcher match = Pattern.compile("\\bpackage=\"([^\"]+)\"").matcher(xml);
                if (!match.find()) throw new IOException("Missing AAR namespace: " + line);
                List<String> rows = new String(zip.getInputStream(symbols).readAllBytes(), StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank()).toList();
                if (rows.isEmpty()) continue;
                libraries.add(new Library(match.group(1), rows));
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    if (entry.isDirectory() || !entry.getName().startsWith("res/")) continue;
                    Path target = unpack.resolve(entry.getName()).normalize();
                    if (!target.startsWith(unpack)) throw new IOException("Invalid AAR entry");
                    Files.createDirectories(target.getParent());
                    try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
                }
                if (Files.isDirectory(unpack.resolve("res"))) {
                    Path flat = unpack.resolve("compiled.zip");
                    run(List.of(args[0], "compile", "--dir", unpack.resolve("res").toString(), "-o", flat.toString()));
                    compiled.add(flat.toString());
                }
            }
        }
        Path manifest = out.resolve("AndroidManifest.xml"), apk = out.resolve("resources/META-INF/rwpp/android-resources.apk");
        Files.createDirectories(apk.getParent());
        Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + GLOBAL + "\"><uses-sdk android:minSdkVersion=\"26\"/><application android:hasCode=\"false\"/></manifest>");
        List<String> link = new ArrayList<>(List.of(args[0], "link", "--manifest", manifest.toString(), "-I", args[1], "--auto-add-overlay", "--package-id", "0x80", "--java", generated.toString(), "-o", apk.toString()));
        for (String path : compiled) link.addAll(List.of("-R", path));
        run(link);
        String globalR = GLOBAL + ".R";
        Set<String> namespaces = new HashSet<>();
        for (Library library : libraries) {
            if (!namespaces.add(library.namespace)) continue;
            Map<String, List<String[]>> types = new LinkedHashMap<>();
            for (String row : library.symbols) {
                String[] fields = row.split("\\s+", 4);
                if (fields.length == 4) types.computeIfAbsent(fields[1], ignored -> new ArrayList<>()).add(fields);
            }
            StringBuilder text = new StringBuilder("package " + library.namespace + ";\npublic final class R {\n");
            for (var type : types.entrySet()) {
                text.append("public static final class ").append(type.getKey()).append(" {\n");
                for (String[] field : type.getValue()) {
                    String value = globalR + "." + field[1] + "." + field[2];
                    if (field[1].equals("styleable")) {
                        if (field[0].equals("int")) value = field[3];
                        else {
                            String content = field[3].replace("{", "").replace("}", "").trim();
                            int size = content.isEmpty() ? 0 : content.split(",").length;
                            String[] attributes = new String[size];
                            for (String[] member : type.getValue()) {
                                if (member[0].equals("int") && member[2].startsWith(field[2] + "_")) {
                                    int position = Integer.decode(member[3]);
                                    if (position < size) attributes[position] = value + "[" + globalR + ".styleable." + member[2] + "]";
                                }
                            }
                            if (Arrays.stream(attributes).anyMatch(Objects::isNull)) throw new IOException("Incomplete styleable: " + library.namespace + "." + field[2]);
                            value = "new int[]{" + String.join(",", attributes) + "}";
                        }
                    }
                    text.append("public static final ").append(field[0]).append(" ").append(field[2]).append(" = ").append(value).append(";\n");
                }
                text.append("}\n");
            }
            text.append("}\n");
            Path file = generated.resolve(library.namespace.replace('.', '/') + "/R.java");
            Files.createDirectories(file.getParent()); Files.writeString(file, text);
        }
        Path classes = out.resolve("classes"); Files.createDirectories(classes);
        List<String> javac = new ArrayList<>(List.of("--release", "8", "-d", classes.toString()));
        try (var paths = Files.walk(generated)) { paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> javac.add(p.toString())); }
        if (ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err, javac.toArray(String[]::new)) != 0)
            throw new IOException("Could not compile extension resource symbols");
        System.out.println("Bundled Android resource namespaces: " + namespaces.size());
    }
}
