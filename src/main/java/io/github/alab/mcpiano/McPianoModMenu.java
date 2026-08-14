package io.github.alab.mcpiano;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu entrypoint for MC Piano's client settings. */
public final class McPianoModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return McPianoConfigScreen::new;
    }
}
