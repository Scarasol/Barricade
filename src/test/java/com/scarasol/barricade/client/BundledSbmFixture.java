package com.scarasol.barricade.client;

import com.google.gson.JsonParser;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.jar.JarFile;

/** Loads the actual bundled library in headless tests without changing production dependencies. */
final class BundledSbmFixture {
    private BundledSbmFixture() { }

    static URLClassLoader open(Path directory) throws Exception {
        var source = Path.of(BedrockCubeBox.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path library = Files.createTempFile(directory, "sbm-", ".jar");
        try (var jar = new JarFile(source.toFile());
             var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("META-INF/jarjar/metadata.json")),
                     StandardCharsets.UTF_8)) {
            var dependency = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("jars").asList().stream()
                    .map(value -> value.getAsJsonObject()).filter(value -> value.getAsJsonObject("identifier")
                            .get("artifact").getAsString().equals("simplebedrockmodel"))
                    .findFirst().orElseThrow();
            try (var input = jar.getInputStream(jar.getJarEntry(dependency.get("path").getAsString()))) {
                Files.copy(input, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        var urls = new ArrayList<URL>();
        urls.add(library.toUri().toURL());
        try (var jar = new JarFile(library.toFile())) {
            for (var entry : jar.stream().filter(value -> value.getName().startsWith("META-INF/jarjar/")
                    && value.getName().endsWith(".jar")).toList()) {
                Path nested = Files.createTempFile(directory, "sbm-dependency-", ".jar");
                try (var input = jar.getInputStream(entry)) {
                    Files.copy(input, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                urls.add(nested.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), BundledSbmFixture.class.getClassLoader());
    }
}
