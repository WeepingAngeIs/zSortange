package dev.anya.storage;

import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public final class HoverCheck {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        if (!Path.of(ItemHelp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar)) {
            throw new AssertionError("Check must exercise the packaged helper");
        }
        var words = new TreeMap<String, String>();
        try (var archive = new ZipFile(jar.toFile());
             var in = archive.getInputStream(archive.getEntry("assets/anyastorage/lang/en_us.json"))) {
            Language.loadFromJson(in, words::put);
        }
        Language original = Language.getInstance();
        Language.inject(new Language() {
            public String getOrDefault(String key, String fallback) { return words.getOrDefault(key, fallback); }
            public boolean has(String key) { return words.containsKey(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return original.getVisualOrder(text); }
        });
        try {
            String prefix = "tooltip.anyastorage.";
            int count = 0;
            for (String key : words.keySet()) {
                if (!key.startsWith(prefix) || !key.endsWith(".1")) continue;
                String id = key.substring(prefix.length(), key.length()-2);
                var lines = new ArrayList<Component>();
                ItemHelp.append(ResourceLocation.fromNamespaceAndPath("anyastorage", id), lines);
                var seen = new HashSet<String>();
                for (int i = 0; i < lines.size(); i++) {
                    String value = lines.get(i).getString();
                    if (!value.equals(words.get(prefix + id + "." + (i + 1))) || !seen.add(value))
                        throw new AssertionError("Unresolved or duplicate hover for " + id);
                }
                if (lines.isEmpty()) throw new AssertionError(id);
                count++;
            }
            var foreign = new ArrayList<Component>();
            ItemHelp.append(ResourceLocation.fromNamespaceAndPath("othermod", "element_magma"), foreign);
            if (!foreign.isEmpty() || count == 0) throw new AssertionError("Wrong namespace or empty coverage");
            for (String name : List.of("stats", "appearance")) {
                String text = Component.translatable(prefix + "backpack." + name, "Alpha", "Bravo").getString();
                if (!text.contains("Alpha") || !text.contains("Bravo") || text.contains("%")) throw new AssertionError(name);
            }
            System.out.println("PASS: packaged hover helper produced distinct translated lines for " + count + " items and rendered both backpack value placeholders.");
        } finally {
            Language.inject(original);
        }
    }
}
