package io.github.alab.mcpiano;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/** Mod Menu configuration screen for the client-local SF2 playback settings. */
public final class McPianoConfigScreen extends Screen {
    private final Screen parent;
    private EditBox midiDirectoryField;
    private EditBox soundFontField;
    private Button dynamicsButton;
    private double weakestDynamics;

    public McPianoConfigScreen(Screen parent) {
        super(Component.literal("MC Piano Configuration"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        PianoClientConfig config = McPianoClient.config();
        weakestDynamics = config.weakestDynamics();
        int left = width / 2 - 155;
        addRenderableWidget(new StringWidget(left, 28, Component.literal("MC Piano client settings"), font));
        addRenderableWidget(new StringWidget(left, 50, Component.literal("MIDI folder (relative to the game directory, or absolute):"), font));
        midiDirectoryField = addRenderableWidget(new EditBox(font, left, 68, 310, 20, Component.literal("MIDI folder")));
        midiDirectoryField.setMaxLength(512);
        midiDirectoryField.setValue(config.midiDirectoryPath());

        addRenderableWidget(new StringWidget(left, 96, Component.literal("SoundFont path (relative to the game directory, or absolute):"), font));
        soundFontField = addRenderableWidget(new EditBox(font, left, 114, 310, 20, Component.literal("SoundFont path")));
        soundFontField.setMaxLength(512);
        soundFontField.setValue(config.soundFontPath());

        dynamicsButton = addRenderableWidget(Button.builder(dynamicsLabel(), button -> { })
                .bounds(left + 70, 154, 170, 20).build());
        addRenderableWidget(Button.builder(Component.literal("- 5%"), button -> adjustDynamics(-5))
                .bounds(left, 154, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ 5%"), button -> adjustDynamics(5))
                .bounds(left + 246, 154, 64, 20).build());
        addRenderableWidget(new StringWidget(left, 182, Component.literal("0% keeps MIDI dynamics; 100% flattens all non-zero notes."), font));

        addRenderableWidget(Button.builder(Component.literal("Restore defaults"), button -> {
                    midiDirectoryField.setValue(PianoClientConfig.DEFAULT_MIDI_DIRECTORY);
                    soundFontField.setValue(PianoClientConfig.DEFAULT_SOUND_FONT);
                    weakestDynamics = PianoClientConfig.DEFAULT_WEAKEST_DYNAMICS;
                    updateDynamicsLabel();
                }).bounds(left, height - 34, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save and close"), button -> saveAndClose())
                .bounds(left + 188, height - 34, 122, 20).build());
    }

    private void adjustDynamics(double change) {
        weakestDynamics = Math.clamp(weakestDynamics + change, 0, 100);
        updateDynamicsLabel();
    }

    private Component dynamicsLabel() {
        return Component.literal(String.format("Weakest dynamics: %.0f%%", weakestDynamics));
    }

    private void updateDynamicsLabel() {
        dynamicsButton.setMessage(dynamicsLabel());
    }

    private void saveAndClose() {
        try {
            McPianoClient.saveConfig(midiDirectoryField.getValue(), soundFontField.getValue(), weakestDynamics);
            onClose();
        } catch (IOException exception) {
            soundFontField.setSuggestion("Could not save: " + exception.getMessage());
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
