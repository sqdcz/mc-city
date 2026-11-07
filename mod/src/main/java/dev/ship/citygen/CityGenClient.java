package dev.ship.citygen;

import net.fabricmc.api.ClientModInitializer;

public final class CityGenClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HowToGoBridge.register();
    }
}
