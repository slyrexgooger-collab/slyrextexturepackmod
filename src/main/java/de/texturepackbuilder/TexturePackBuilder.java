package de.texturepackbuilder;

import de.texturepackbuilder.gui.ItemsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
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

    /** Hotkey zum Öffnen (Standard: K, änderbar unter Optionen → Steuerung). */
    public static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.texturepackbuilder.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null) client.setScreen(new ItemsScreen(null));
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof PackSelectionScreen) {
                Screens.getButtons(screen).add(
                        Button.builder(Component.translatable("tpb.button.open"),
                                        b -> client.setScreen(new ItemsScreen(screen)))
                                .bounds(5, 5, 100, 20)
                                .build());
            }
        });
    }
}
