package de.texturepackbuilder.core;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Alle Ordner, die die Mod benutzt. */
public final class Dirs {
    /** Marker-Datei in gebauten Packs, damit sie nicht wieder als Quelle eingelesen werden. */
    public static final String GENERATED_MARKER = "tpb_generated.txt";

    private Dirs() {}

    private static Path gameDir() {
        String override = System.getProperty("tpb.gameDir"); // für Tests ohne Minecraft
        if (override != null) return Path.of(override);
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    /** Der normale resourcepacks-Ordner von Minecraft. */
    public static Path resourcepacks() {
        return gameDir().resolve("resourcepacks");
    }

    /** Hier landen die von Modrinth heruntergeladenen Packs. */
    public static Path downloads() {
        return gameDir().resolve("texturepackbuilder").resolve("downloads");
    }

    /** Hier landen die im Spiel hochgeladenen (importierten) eigenen Packs. */
    public static Path imported() {
        return gameDir().resolve("texturepackbuilder").resolve("imported");
    }

    /** Gespeicherte Auswahl (Texturpfad → gewählte Variante). */
    public static Path selectionFile() {
        return gameDir().resolve("config").resolve("texturepackbuilder.json");
    }
}
