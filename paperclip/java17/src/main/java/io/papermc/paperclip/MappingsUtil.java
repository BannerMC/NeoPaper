package io.papermc.paperclip;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import net.fabricmc.installer.util.Utils;
import net.fabricmc.lorenztiny.TinyMappingFormat;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingFormats;
import org.cadixdev.lorenz.merge.MappingSetMerger;

import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MappingsUtil {
    private MappingsUtil() {}

    public static void generateIntermediaryToSpigotMappings(final Path cache, String gameVersion, String spigotMappingsRef, String mojangMappingsRef) {
        final Path intermediary = cache.resolve("inter.jar");
        final Path intermediaryMaps = cache.resolve("intermediary.tiny");
        try {
            Utils.downloadFile(new URL(String.format("https://maven.fabricmc.net/net/fabricmc/intermediary/%s/intermediary-%s-v2.jar", gameVersion, gameVersion)), intermediary);
        } catch (final IOException e) {
            throw new RuntimeException("Couldn't download intermediary v2: ", e);
        }

        try (ZipInputStream zipInputStream = new ZipInputStream(intermediary.toUri().toURL().openStream())) {
            for (ZipEntry zipEntry = zipInputStream.getNextEntry(); zipEntry != null; zipEntry = zipInputStream.getNextEntry()) {
                if (zipEntry.getName().equals("mappings/mappings.tiny")) {
                    try (
                        final ReadableByteChannel inputChannel = Channels.newChannel(zipInputStream);
                        final FileChannel outputChannel = FileChannel.open(intermediaryMaps, CREATE, WRITE, TRUNCATE_EXISTING)
                    ) {
                        outputChannel.transferFrom(inputChannel, 0, Long.MAX_VALUE);
                    }
                    break;
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException("Couldn't extract intermediary mappings: ", e);
        }

        final Path runtimeMaps = cache.resolve("runtime.mapping");

        boolean useSpigot = true;
        try {
            if (useSpigot) {
                Utils.downloadFile(new URL(String.format("https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/raw/mappings/bukkit-%s-cl.csrg?at=%s", gameVersion, spigotMappingsRef)), runtimeMaps);
            } else {
                Utils.downloadFile(new URL(String.format("https://piston-data.mojang.com/v1/objects/%s/server.txt", mojangMappingsRef)), runtimeMaps);
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't download spigot mappings: ", e);
        }

        final Path intermediaryToSpigotMaps = cache.resolve("output.tiny");

        try {
            MappingSet intermediarySet = TinyMappingFormat.TINY_2.read(intermediaryMaps, "official", "intermediary");
            MappingSet namedSet;
            if (useSpigot) {
                namedSet = MappingFormats.CSRG.read(runtimeMaps);
            } else {
                namedSet = MappingFormats.byId("proguard").read(runtimeMaps);
            }
            MappingSet merge = MappingSetMerger.create(
                namedSet.reverse(),
                intermediarySet
            ).merge();
            TinyMappingFormat.TINY.write(merge.reverse(), intermediaryToSpigotMaps, "intermediary", "spigot");
            System.setProperty("banner.mappings", intermediaryToSpigotMaps.toAbsolutePath().toString()); // Used in fabric loader in IntermediarySpigotMappings.
        } catch (final IOException e) {
            throw new RuntimeException("Failed to merge intermediary with spigot mappings: ", e);
        }
    }
}
