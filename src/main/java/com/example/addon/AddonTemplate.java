package com.example.addon;

import com.example.addon.hud.ImageHud;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final HudGroup HUD_GROUP = new HudGroup("HUD+");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HUD+");

        Hud.get().register(ImageHud.INFO);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
