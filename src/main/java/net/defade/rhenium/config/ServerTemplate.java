package net.defade.rhenium.config;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @param memory in Mi
 */
public record ServerTemplate(String templateName, String dockerImage, int maxPlayers, int cpus, int memory) {
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    public String templateIdentifier() {
        return generateAlphanumericHash(templateName);
    }

    private static String generateAlphanumericHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes());

            BigInteger hashNumber = new BigInteger(1, hashBytes);

            return toBase62(hashNumber);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating hash: " + e.getMessage(), e);
        }
    }

    private static String toBase62(BigInteger number) {
        StringBuilder base62 = new StringBuilder();
        BigInteger base = BigInteger.valueOf(BASE62.length());

        while (number.compareTo(BigInteger.ZERO) > 0) {
            int remainder = number.mod(base).intValue();
            base62.insert(0, BASE62.charAt(remainder));
            number = number.divide(base);
        }

        return base62.toString();
    }
}
