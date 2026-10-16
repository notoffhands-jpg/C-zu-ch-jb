package de.fiden.botsystem.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fiden.botsystem.BotSystemPlugin;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Skins from PNG files in plugins/BotSystem/skins/.
 *
 * Minecraft clients only draw skins that Mojang has signed, so a raw PNG can't be handed to
 * vanilla clients. Two ways around that:
 *  - the PNG travels inside the bot's profile as the "botsystem_skin" property and the
 *    BotSystem client mod draws it (no account needed, only players with the mod see it);
 *  - with a MineSkin API key (/bot mineskin key <key>), the PNG is signed once via mineskin.org and
 *    cached by content hash, so every player sees it.
 */
public final class SkinFiles {
    public static final String PROPERTY = "botsystem_skin";
    public static final String FILE_PROPERTY = "botsystem_skinfile";
    // The PNG travels base64-encoded in a profile property, which may be at most 32767 chars.
    private static final int MAX_BYTES = 22 * 1024;
    private static final Map<String, String[]> SIGNED = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static boolean cacheLoaded;

    private SkinFiles() {
    }

    public static Path folder() {
        Path dir = BotSystemPlugin.configDir().resolve("skins");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }

        return dir;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder(), "*.png")) {
            for (Path file : files) {
                names.add(file.getFileName().toString());
            }
        } catch (IOException ignored) {
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** A validated skin file, or an exception whose message explains what's wrong. */
    public record Skin(String name, byte[] png, boolean slim, String hash) {
        /** Property value the client mod reads: "slim:" or "classic:" followed by the PNG in base64. */
        public String propertyValue() {
            return (this.slim ? "slim:" : "classic:") + Base64.getEncoder().encodeToString(this.png);
        }
    }

    public static Skin load(String requested, String variant) throws IOException {
        String name = requested.toLowerCase(Locale.ROOT).endsWith(".png") ? requested : requested + ".png";
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("Ungueltiger Dateiname: " + requested);
        }

        Path file = folder().resolve(name);
        if (!Files.isRegularFile(file)) {
            // Case-insensitive match, so "Steve" finds "steve.PNG".
            file = null;
            for (String existing : names()) {
                if (existing.equalsIgnoreCase(name)) {
                    file = folder().resolve(existing);
                    name = existing;
                }
            }

            if (file == null) {
                throw new IOException("Datei " + name + " nicht gefunden in " + folder());
            }
        }

        byte[] png = Files.readAllBytes(file);
        if (png.length > MAX_BYTES) {
            throw new IOException(name + " ist zu gross (" + png.length / 1024 + " KB, max 22 KB).");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException(name + " ist kein gueltiges PNG.");
        }

        if (image.getWidth() != 64 || image.getHeight() != 64 && image.getHeight() != 32) {
            throw new IOException(name + " ist " + image.getWidth() + "x" + image.getHeight() + " - Skins muessen 64x64 (oder alt 64x32) sein.");
        }

        if (image.getHeight() == 32) {
            png = upgradeLegacy(image);
            image = ImageIO.read(new ByteArrayInputStream(png));
        }

        boolean slim = switch (variant == null ? "auto" : variant.toLowerCase(Locale.ROOT)) {
            case "slim", "alex", "duenn" -> true;
            case "classic", "steve", "normal", "breit" -> false;
            default -> (image.getRGB(54, 20) >>> 24) == 0;
        };
        return new Skin(name, png, slim, sha256(png));
    }

    /** Old 64x32 skins: copy them onto a 64x64 canvas (the client mod wants the modern layout). */
    private static byte[] upgradeLegacy(BufferedImage legacy) throws IOException {
        BufferedImage modern = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        modern.getGraphics().drawImage(legacy, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(modern, "png", out);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- MineSkin

    public static boolean hasApiKey() {
        String key = BotDefaults.get().mineskinKey;
        return key != null && !key.isBlank();
    }

    /** Cached signed texture {value, signature} for this file content, or null. */
    public static String[] signedFor(Skin skin) {
        loadCache();
        return SIGNED.get(skin.hash() + (skin.slim() ? ":slim" : ":classic"));
    }

    /**
     * Uploads the PNG to mineskin.org once and returns Mojang-signed {value, signature}.
     * Runs off the server thread; completes exceptionally with a readable message on failure.
     */
    public static CompletableFuture<String[]> sign(Skin skin) {
        String[] cached = signedFor(skin);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        String key = BotDefaults.get().mineskinKey;
        String boundary = "----BotSystem" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            field(body, boundary, "variant", skin.slim() ? "slim" : "classic");
            field(body, boundary, "visibility", "unlisted");
            field(body, boundary, "name", skin.name().replaceAll("\\.png$", ""));
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + skin.name()
                    + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(skin.png());
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException impossible) {
            return CompletableFuture.failedFuture(impossible);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/generate"))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "BotSystem/3.0")
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            JsonObject json;
            try {
                json = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (RuntimeException error) {
                throw new IllegalStateException("MineSkin antwortete mit HTTP " + response.statusCode());
            }

            if (response.statusCode() != 200 || !json.has("skin")) {
                String message = json.has("errors") && json.getAsJsonArray("errors").size() > 0
                    ? json.getAsJsonArray("errors").get(0).getAsJsonObject().get("message").getAsString()
                    : "HTTP " + response.statusCode();
                throw new IllegalStateException("MineSkin: " + message);
            }

            JsonObject data = json.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
            String[] signed = new String[]{data.get("value").getAsString(), data.get("signature").getAsString()};
            SIGNED.put(skin.hash() + (skin.slim() ? ":slim" : ":classic"), signed);
            saveCache();
            return signed;
        });
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    private static Path cacheFile() {
        return folder().resolve(".mineskin-cache.json");
    }

    private static synchronized void loadCache() {
        if (cacheLoaded) {
            return;
        }

        cacheLoaded = true;
        try {
            if (Files.exists(cacheFile())) {
                JsonObject root = JsonParser.parseString(Files.readString(cacheFile())).getAsJsonObject();
                for (String key : root.keySet()) {
                    JsonObject entry = root.getAsJsonObject(key);
                    SIGNED.put(key, new String[]{entry.get("value").getAsString(), entry.get("signature").getAsString()});
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static synchronized void saveCache() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, String[]> entry : SIGNED.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("value", entry.getValue()[0]);
            item.addProperty("signature", entry.getValue()[1]);
            root.add(entry.getKey(), item);
        }

        try {
            Files.writeString(cacheFile(), root.toString());
        } catch (IOException ignored) {
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
