package de.texturepackbuilder;

import de.texturepackbuilder.gui.TexturesScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Einstieg der Mod. Fügt im Hauptmenü und im Ressourcenpaket-Menü einen
 * Button "Pack Builder" hinzu, der den Auswahl-Bildschirm öffnet.
 */
public class TexturePackBuilder implements ClientModInitializer {
    public static final String MOD_ID = "texturepackbuilder";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    /** Minecraft-Version, für die auf Modrinth gesucht wird. */
    public static final String GAME_VERSION = "1.21.11";
    /** Hintergrund-Threads für Scannen, Downloads und Export. */
    public static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TexturePackBuilder-Worker");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof PackSelectionScreen) {
                Screens.getButtons(screen).add(
                        Button.builder(Component.translatable("tpb.button.open"),
                                        b -> client.setScreen(new TexturesScreen(screen)))
                                .bounds(5, 5, 100, 20)
                                .build());
            }
        });
    }
}
