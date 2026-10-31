package de.tpacheck;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TpaInvseeCheck implements ClientModInitializer {
    public static final String MOD_ID = "tpa_invsee_check";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    public static TpaConfig CONFIG = new TpaConfig();

    @Override
    public void onInitializeClient() {
        CONFIG = TpaConfig.load();
        TpaChecker.init();
        LOG.info("TPA Invsee Check geladen ({} Regeln)", CONFIG.rules.size());
    }
}
