import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.core.ExtensionDependencyClassLoader;
import io.github.rwml.core.ExtensionDependencyResolver;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;
import io.github.rwml.core.ExtensionPackImporter;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Exercises the actual RWML importer and dependency class-loader boundary.
 */
public final class ExtensionPackCheck {
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        boolean android = args[0].equals("android");
        Path staging = Path.of(args[2]);
        Files.createDirectories(staging);
        List<ExtensionDescriptor> descriptors = new ArrayList<>();
        var result = ExtensionPackImporter.importPack(Path.of(args[1]), staging, (jar, member) -> {
            try {
                var descriptor = ExtensionDescriptorReader.read(jar);
                require(Set.of("io.github.rwpp.api", "io.github.rwpp").contains(descriptor.id()), "Unexpected extension ID");
                Path installed = staging.resolve(descriptor.id() + ".jar");
                Files.copy(jar, installed, StandardCopyOption.REPLACE_EXISTING);
                descriptors.add(ExtensionDescriptorReader.read(installed));
                return null;
            } catch (io.github.rwml.api.ExtensionException failure) {
                throw new java.io.IOException(failure);
            }
        });
        require(result.report().failed().isEmpty(), result.report().failed().toString());
        require(descriptors.size() == 2, "Pack must contain two independent extensions");
        var resolution = new ExtensionDependencyResolver().resolve(descriptors);
        require(resolution.excluded().isEmpty(), resolution.excluded().toString());
        var order = resolution.loadOrder();
        require(order.get(0).id().equals("io.github.rwpp.api"), "API must load first");
        require(order.get(1).id().equals("io.github.rwpp"), "Client must load after API");
        for (var descriptor : order) {
            require(descriptor.version().equals(result.manifest().pins().get(descriptor.id())), "Missing version pin");
            require(result.manifest().enabled().contains(descriptor.id()), "Extension not enabled by pack");
        }
        require(order.get(0).uiFramework().isBlank(), "API must not claim the UI");
        require(order.get(1).uiFramework().equals("io.github.rwpp.ui.RwppUiFramework"), "Client UI not declared");
        Path api = staging.resolve("io.github.rwpp.api.jar");
        Path client = staging.resolve("io.github.rwpp.jar");
        try (ZipFile a = new ZipFile(api.toFile()); ZipFile c = new ZipFile(client.toFile())) {
            var apiClasses = a.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).collect(java.util.stream.Collectors.toSet());
            var duplicate = c.stream().map(e -> e.getName()).filter(apiClasses::contains).toList();
            require(duplicate.isEmpty(), "Duplicate class ownership: " + duplicate);
            require((a.getEntry("classes.dex") != null) == android, "Wrong API platform");
            require((c.getEntry("classes.dex") != null) == android, "Wrong client platform");
            if (android) {
                require(a.getEntry("org/slf4j/impl/LoggerServiceProvider.class") != null, "API logging backend missing");
                Set<String> apiDex = dexClasses(a), clientDex = dexClasses(c);
                require(apiDex.contains("Lio/github/rwpp/api/ApiRuntime;"), "API entry missing from DEX");
                require(clientDex.contains("Lio/github/rwpp/ui/RwppUiFramework;"), "Client UI missing from DEX");
                apiDex.retainAll(clientDex);
                require(apiDex.isEmpty(), "Duplicate DEX class ownership: " + apiDex);
            }
        }
        if (!android) {
            ClassLoader host = ExtensionPackCheck.class.getClassLoader();
            try (var apiLoader = new URLClassLoader(new URL[]{api.toUri().toURL()}, host);
                 var clientLoader = new URLClassLoader(new URL[]{client.toUri().toURL()},
                         new ExtensionDependencyClassLoader(host, List.of(apiLoader)))) {
                for (String name : List.of("io.github.rwpp.api.ApiRuntime", "org.koin.core.Koin", "org.slf4j.LoggerFactory")) {
                    require(Class.forName(name, false, apiLoader) == Class.forName(name, false, clientLoader), name + " has split identity");
                }
                Object framework = Class.forName(order.get(1).uiFramework(), true, clientLoader).getConstructor().newInstance();
                require(framework instanceof UiFramework, "UI framework cannot be instantiated through its dependency loader");
                Class.forName("io.github.rwpp.api.game.audio.SoundDecoding_jvmKt", false, clientLoader)
                        .getMethod("decodePlatformSound", byte[].class, String.class);
                // The desktop backend uses the game's Slick Ogg decoder and its OpenAL
                // context. Decode the packaged assets without requiring an audio device.
                Class<?> decoderType = Class.forName("org.newdawn.slick.openal.OggDecoder", true, host);
                Object decoder = decoderType.getConstructor().newInstance();
                var getData = decoderType.getMethod("getData", java.io.InputStream.class);
                try (var clientArchive = new ZipFile(client.toFile())) {
                    var sounds = clientArchive.stream().filter(e -> e.getName().endsWith(".ogg")).toList();
                    require(!sounds.isEmpty(), "Client UI sound assets missing");
                    for (var entry : sounds) {
                        try (var sound = clientArchive.getInputStream(entry)) {
                            Object decoded = getData.invoke(decoder, new java.io.BufferedInputStream(sound));
                            Class<?> data = decoded.getClass();
                            int channels = data.getField("channels").getInt(decoded);
                            int rate = data.getField("rate").getInt(decoded);
                            ByteBuffer samples = (ByteBuffer) data.getField("data").get(decoded);
                            require((channels == 1 || channels == 2) && rate > 0 && samples.remaining() > 0,
                                    "Desktop sound did not decode: " + entry.getName());
                        }
                    }
                }
                var loggerFactory = Class.forName("org.slf4j.LoggerFactory", true, apiLoader);
                Object logger = loggerFactory.getMethod("getILoggerFactory").invoke(null);
                require(logger.getClass().getName().equals("org.slf4j.simple.SimpleLoggerFactory"), "API cannot discover its logging backend");
                Object mainDispatcher = Class.forName("kotlinx.coroutines.Dispatchers", true, apiLoader)
                        .getMethod("getMain").invoke(null);
                require(mainDispatcher.getClass().getName().startsWith("kotlinx.coroutines.swing."),
                        "API cannot discover Dispatchers.Main: " + mainDispatcher);

                // Client bootstrap may install its backend and record its own context;
                // it must never bootstrap the API again under the client's identity.
                var context = (ExtensionContext) Proxy.newProxyInstance(host, new Class<?>[]{ExtensionContext.class}, (proxy, method, values) -> {
                    if (method.getName().equals("log")) return null;
                    throw new AssertionError("Client attempted API initialization: " + method.getName());
                });
                try {
                    ((Extension) Class.forName(order.get(1).mainClass(), true, clientLoader).getConstructor().newInstance()).onLoad(context);
                } finally {
                    Class<?> runtime = Class.forName("io.github.rwpp.api.net.transport.TransportRuntime", true, apiLoader);
                    runtime.getMethod("close").invoke(runtime.getField("INSTANCE").get(null));
                }
            }
        }
        System.out.println("PACK_OK platform=" + args[0] + " extensions=2 order=api,client ui=client duplicateClasses=0");
    }

    private static Set<String> dexClasses(ZipFile archive) throws Exception {
        Set<String> result = new HashSet<>();
        for (var entry : archive.stream().filter(e -> e.getName().matches("classes[0-9]*\\.dex")).toList()) {
            byte[] bytes;
            try (var stream = archive.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
            ByteBuffer dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            require(dex.getInt(0) == 0x0a786564, "Invalid DEX header");
            int strings = dex.getInt(60), types = dex.getInt(68);
            int size = dex.getInt(96), definitions = dex.getInt(100);
            for (int i = 0; i < size; i++) {
                int type = dex.getInt(definitions + i * 32);
                int string = dex.getInt(types + type * 4);
                int offset = dex.getInt(strings + string * 4);
                while ((bytes[offset++] & 0x80) != 0) { /* Skip UTF-16 length (ULEB128). */ }
                int end = offset;
                while (bytes[end] != 0) end++;
                result.add(new String(bytes, offset, end - offset, StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
