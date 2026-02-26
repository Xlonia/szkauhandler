package dev.xlonia.szkauhandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Map<String, String> customCurrencies = new HashMap<>();
    private final Map<UUID, Long> bannedPlayers = new HashMap<>();
    private final Set<String> blockedItems = new HashSet<>();
    private final Map<UUID, Set<String>> playerBlockedItems = new HashMap<>();
    private final Map<UUID, Boolean> infiniteMode = new HashMap<>();
    
    public ConfigManager() {
        // 默认货币
        customCurrencies.put("DA", "minecraft:diamond");
        customCurrencies.put("SP", "minecraft:spore_blossom");
        customCurrencies.put("AD", "minecraft:ancient_debris");
    }
    
    public void addCustomCurrency(String code, String itemId) {
        customCurrencies.put(code, itemId);
    }
    
    public void removeCustomCurrency(String code) {
        customCurrencies.remove(code);
    }
    
    public String getCurrencyItemId(String code) {
        return customCurrencies.getOrDefault(code, code);
    }
    
    public Map<String, String> getCustomCurrencies() {
        return customCurrencies;
    }
    
    public void banPlayer(UUID playerId, long durationSeconds) {
        if (durationSeconds <= 0) {
            bannedPlayers.remove(playerId);
        } else {
            bannedPlayers.put(playerId, System.currentTimeMillis() + (durationSeconds * 1000));
        }
    }
    
    public void unbanPlayer(UUID playerId) {
        bannedPlayers.remove(playerId);
    }
    
    public boolean isPlayerBanned(UUID playerId) {
        Long banEndTime = bannedPlayers.get(playerId);
        if (banEndTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > banEndTime) {
            bannedPlayers.remove(playerId);
            return false;
        }
        return true;
    }
    
    public void blockItem(String itemId) {
        blockedItems.add(itemId);
    }
    
    public void unblockItem(String itemId) {
        blockedItems.remove(itemId);
    }
    
    public void blockItemForPlayer(UUID playerId, String itemId) {
        playerBlockedItems.computeIfAbsent(playerId, k -> new HashSet<>()).add(itemId);
    }
    
    public void unblockItemForPlayer(UUID playerId, String itemId) {
        Set<String> items = playerBlockedItems.get(playerId);
        if (items != null) {
            items.remove(itemId);
        }
    }
    
    public boolean isItemBlocked(String itemId, UUID playerId) {
        if (blockedItems.contains(itemId)) {
            return true;
        }
        Set<String> playerItems = playerBlockedItems.get(playerId);
        return playerItems != null && playerItems.contains(itemId);
    }
    
    public void setInfiniteMode(UUID playerId, boolean enabled) {
        infiniteMode.put(playerId, enabled);
    }
    
    public boolean isInfiniteModeEnabled(UUID playerId) {
        return infiniteMode.getOrDefault(playerId, false);
    }
    
    public Set<String> getBlockedItems() {
        return blockedItems;
    }
    
    public Map<UUID, Set<String>> getPlayerBlockedItems() {
        return playerBlockedItems;
    }
    
    public Map<UUID, Long> getBannedPlayers() {
        return bannedPlayers;
    }
    
    public Map<UUID, Boolean> getInfiniteModePlayers() {
        return infiniteMode;
    }
    
    public void saveConfig() {
        try {
            Path dataDir = FMLPaths.CONFIGDIR.get().resolve("szkauhandler");
            Files.createDirectories(dataDir);
            
            // 保存配置
            Path configFile = dataDir.resolve("config.json");
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                ConfigData data = new ConfigData();
                data.customCurrencies = customCurrencies;
                data.bannedPlayers = bannedPlayers;
                data.blockedItems = blockedItems;
                data.playerBlockedItems = playerBlockedItems;
                data.infiniteMode = infiniteMode;
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("保存配置失败: {}", e.getMessage());
        }
    }
    
    public void loadConfig() {
        try {
            Path dataDir = FMLPaths.CONFIGDIR.get().resolve("szkauhandler");
            Path configFile = dataDir.resolve("config.json");
            
            if (Files.exists(configFile)) {
                Gson gson = new Gson();
                try (Reader reader = Files.newBufferedReader(configFile)) {
                    ConfigData data = gson.fromJson(reader, ConfigData.class);
                    if (data != null) {
                        if (data.customCurrencies != null) {
                            customCurrencies.putAll(data.customCurrencies);
                        }
                        if (data.bannedPlayers != null) {
                            bannedPlayers.putAll(data.bannedPlayers);
                        }
                        if (data.blockedItems != null) {
                            blockedItems.addAll(data.blockedItems);
                        }
                        if (data.playerBlockedItems != null) {
                            playerBlockedItems.putAll(data.playerBlockedItems);
                        }
                        if (data.infiniteMode != null) {
                            infiniteMode.putAll(data.infiniteMode);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("加载配置失败: {}", e.getMessage());
        }
    }
    
    private static class ConfigData {
        public Map<String, String> customCurrencies;
        public Map<UUID, Long> bannedPlayers;
        public Set<String> blockedItems;
        public Map<UUID, Set<String>> playerBlockedItems;
        public Map<UUID, Boolean> infiniteMode;
    }
}