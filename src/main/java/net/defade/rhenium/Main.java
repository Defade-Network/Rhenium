package net.defade.rhenium;

import net.defade.rhenium.config.RheniumConfig;

public class Main {
    public static void main(String[] args) {
        RheniumConfig rheniumConfig = RheniumConfig.load();

        Rhenium rhenium = new Rhenium(rheniumConfig);
        rhenium.start();
    }
}
