package net.defade.rhenium;

import io.kubernetes.client.openapi.ApiException;
import net.defade.rhenium.config.RheniumConfig;
import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException, ApiException {
        RheniumConfig rheniumConfig = RheniumConfig.load();

        Rhenium rhenium = new Rhenium(rheniumConfig);
        rhenium.start();
    }
}
