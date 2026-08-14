package xyz.jpenilla.squaremap.addon.skins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import xyz.jpenilla.squaremap.api.SquaremapProvider;

public final class SquaremapSkins extends JavaPlugin {
    private static final int HTTP_CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int HTTP_READ_TIMEOUT_MILLIS = 10000;

    private static SquaremapSkins instance;
    private static File skinsDir;

    // Downloads run on a single dedicated thread instead of the shared Bukkit async
    // pool - a slow or unresponsive texture server must never pile up scheduler threads.
    private ExecutorService downloadExecutor;
    private final Map<UUID, String> savedTextureUrls = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public SquaremapSkins() {
        instance = this;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        skinsDir = new File(SquaremapProvider.get().webDir().toFile(), "skins");
        if (!skinsDir.exists() && !skinsDir.mkdirs()) {
            getLogger().severe("Could not create skins directory!");
            getLogger().severe("Check your file permissions and try again");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.downloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "squaremap-skins-downloader");
            thread.setDaemon(true);
            return thread;
        });

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onPlayerJoin(PlayerJoinEvent event) {
                new FetchSkinURL(event.getPlayer()).runTaskLater(instance, 5);
            }
        }, this);

        // update-interval is documented in seconds; convert to ticks.
        long interval = getConfig().getInt("update-interval", 60) * 20L;
        new UpdateTask().runTaskTimer(instance, interval, interval);
    }

    @Override
    public void onDisable() {
        if (this.downloadExecutor != null) {
            this.downloadExecutor.shutdownNow();
            try {
                this.downloadExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.downloadExecutor = null;
        }
        this.savedTextureUrls.clear();
        this.inFlight.clear();
    }

    private static class UpdateTask extends BukkitRunnable {
        @Override
        public void run() {
            Bukkit.getOnlinePlayers().forEach(player ->
                new FetchSkinURL(player).runTask(instance));
        }
    }

    private static final class FetchSkinURL extends BukkitRunnable {
        private final Player player;

        private FetchSkinURL(Player player) {
            this.player = player;
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                return;
            }
            String url = getTexture(player);
            if (url == null || url.isEmpty()) {
                return;
            }
            instance.queueDownload(player.getUniqueId(), player.getName(), url);
        }
    }

    private void queueDownload(UUID playerId, String name, String url) {
        if (url.equals(this.savedTextureUrls.get(playerId)) && new File(skinsDir, name + ".png").isFile()) {
            return;
        }
        if (!this.inFlight.add(playerId)) {
            return;
        }
        ExecutorService executor = this.downloadExecutor;
        if (executor == null) {
            this.inFlight.remove(playerId);
            return;
        }
        executor.execute(() -> {
            try {
                if (saveTexture(name, url)) {
                    this.savedTextureUrls.put(playerId, url);
                }
            } finally {
                this.inFlight.remove(playerId);
            }
        });
    }

    private static String getTexture(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures")) {
                try {
                    String data = property.getValue();
                    byte[] base64 = Base64.getDecoder().decode(data);
                    String json = new String(base64);
                    JSONObject obj = (JSONObject) new JSONParser().parse(json);
                    JSONObject obj2 = (JSONObject) obj.get("textures");
                    JSONObject obj3 = (JSONObject) obj2.get("SKIN");
                    return (String) obj3.get("url");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private boolean saveTexture(String name, String url) {
        try {
            URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MILLIS);
            BufferedImage skin;
            try (InputStream in = connection.getInputStream()) {
                skin = ImageIO.read(in);
            }
            if (skin == null) {
                this.getSLF4JLogger().warn("Could not decode texture {} for {}", url, name);
                return false;
            }
            BufferedImage img = skin.getSubimage(8, 8, 8, 8);
            File file = new File(skinsDir, name + ".png");
            ImageIO.write(img, "png", file);
            return true;
        } catch (Exception e) {
            this.getSLF4JLogger().info("Could not save texture {} to {}", url, new File(skinsDir, name + ".png"), e);
            return false;
        }
    }
}
