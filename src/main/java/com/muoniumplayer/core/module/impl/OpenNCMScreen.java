package com.muoniumplayer.core.module.impl;

import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.features.ExtensionModule;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.BooleanValue;
import today.opai.api.interfaces.modules.values.ModeValue;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.ClientSettings;

/**
 * @author IzumiiKonata
 * Date: 2026/4/1 11:08
 */
public class OpenNCMScreen extends ExtensionModule implements SharedConstants, EventHandler {

    public OpenNCMScreen() {
        super("MuoniumPlayer", "Open MuoniumPlayer GUI", EnumModuleCategory.MISC);
        this.setEventHandler(this);

        this.addValues(this.quality, this.boundaries, this.lyricDebug);

        this.boundaries.setValueCallback(b -> ClientSettings.SHOW_WIDGET_BOUNDARY = b);
        this.lyricDebug.setValueCallback(b -> ClientSettings.DEBUG_MODE = b);

        this.quality.setValueCallback(str -> {

            for (Quality value : Quality.values()) {
                if (value.getQuality().equalsIgnoreCase(str)) {
                    CloudMusic.quality = value;
                    break;
                }
            }

        });
    }

    public ModeValue quality = api.getValueManager().createModes("Music Quality", "LossLess", new String[] { "Standard", "Higher", "ExHigh", "LossLess", "HiRes", "JyEffect", "Sky", "JyMaster" });
    public BooleanValue boundaries = api.getValueManager().createBoolean("Show UI Widget Boundary", false);
    public BooleanValue lyricDebug = api.getValueManager().createBoolean("Per-word lyrics debug", false);

    @Override
    public void onTick() {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        api.displayScreen(NCMScreen.getInstance());
    }
}
