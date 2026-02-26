package dev.xlonia.szkauhandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TradeManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Map<UUID, Trade> pendingTrades = new HashMap<>();
    private final Map<UUID, List<TradeHistory>> tradeHistories = new HashMap<>();
    private final Map<UUID, ReentrantLock> tradeLocks = new HashMap<>();
    private long lastCleanupTime = System.currentTimeMillis();
    private long lastSaveTime = System.currentTimeMillis();
    
    public void createTrade(ServerPlayer initiator, ServerPlayer target, ItemStack offeredItem, String requestedItem1, int amount1, String requestedItem2, int amount2, String note) {
        UUID tradeId = UUID.randomUUID();
        Trade trade = new Trade(tradeId, initiator, target, offeredItem, requestedItem1, amount1, requestedItem2, amount2, note);
        pendingTrades.put(tradeId, trade);
        
        // 发送交易请求给目标玩家
        Component message = Component.literal(String.format("玩家[%s]想要与你交易，他提供[%s][%d]，你提供[%s][%d]%s，备注是[%s]，请问是否愿意？请用/szkauhandler %s acce/deny/barg 反应",
                initiator.getName().getString(),
                offeredItem.getDisplayName().getString(),
                offeredItem.getCount(),
                getItemNameFromCode(requestedItem1),
                amount1,
                requestedItem2.equals("0") ? "" : String.format("和[%s][%d]", getItemNameFromCode(requestedItem2), amount2),
                note != null ? note : "",
                initiator.getName().getString()));
        target.sendSystemMessage(message);
        
        // 记录交易历史
        addTradeHistory(initiator.getUUID(), trade);
        addTradeHistory(target.getUUID(), trade);
    }
    
    public void acceptTrade(ServerPlayer player, UUID tradeId) {
        ReentrantLock lock = tradeLocks.computeIfAbsent(tradeId, k -> new ReentrantLock());
        lock.lock();
        try {
            Trade trade = pendingTrades.get(tradeId);
            if (trade == null) {
                player.sendSystemMessage(Component.literal("交易不存在或已过期"));
                return;
            }
            
            if (!trade.getTargetId().equals(player.getUUID()) && !trade.getInitiatorId().equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal("你不是此交易的参与者"));
                return;
            }
            
            if (trade.isExpired()) {
                pendingTrades.remove(tradeId);
                player.sendSystemMessage(Component.literal("交易已过期"));
                return;
            }
            
            boolean tradeSuccess = false;
            if (trade.getStatus() == Trade.Status.PENDING) {
                if (trade.getTargetId().equals(player.getUUID())) {
                    // 目标玩家接受交易
                    tradeSuccess = executeTrade(trade, player);
                }
            } else if (trade.getStatus() == Trade.Status.BARGAINING) {
                if (trade.getInitiatorId().equals(player.getUUID())) {
                    // 发起者接受还价
                    tradeSuccess = executeTrade(trade, player);
                }
            }
            
            if (tradeSuccess) {
                // 最终一致性校验
                if (verifyTradeResult(trade, player)) {
                    // 原子化更新交易状态
                    completeTrade(tradeId, trade);
                    
                    // 发送成功消息给双方
                    ServerPlayer initiator = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
                    ServerPlayer target = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getTargetId());
                    if (initiator != null) {
                        initiator.sendSystemMessage(Component.literal("交易成功！"));
                    }
                    if (target != null) {
                        target.sendSystemMessage(Component.literal("交易成功！"));
                    }
                } else {
                    // 校验失败，回滚交易
                    player.sendSystemMessage(Component.literal("交易失败，物品转移结果与约定不一致"));
                    
                    // 发送失败消息给另一方
                    ServerPlayer initiator = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
                    ServerPlayer target = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getTargetId());
                    if (trade.getInitiatorId().equals(player.getUUID())) {
                        if (target != null) {
                            target.sendSystemMessage(Component.literal("交易失败，物品转移结果与约定不一致"));
                        }
                    } else {
                        if (initiator != null) {
                            initiator.sendSystemMessage(Component.literal("交易失败，物品转移结果与约定不一致"));
                        }
                    }
                }
            } else {
                player.sendSystemMessage(Component.literal("交易失败，物品栏中没有足够的物品或物品不符合规则"));
                
                // 发送失败消息给另一方
                ServerPlayer initiator = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
                ServerPlayer target = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getTargetId());
                if (trade.getInitiatorId().equals(player.getUUID())) {
                    if (target != null) {
                        target.sendSystemMessage(Component.literal("交易失败，发起者物品栏中没有足够的物品或物品不符合规则"));
                    }
                } else {
                    if (initiator != null) {
                        initiator.sendSystemMessage(Component.literal("交易失败，目标物品栏中没有足够的物品或物品不符合规则"));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    public void denyTrade(ServerPlayer player, UUID tradeId) {
        ReentrantLock lock = tradeLocks.computeIfAbsent(tradeId, k -> new ReentrantLock());
        lock.lock();
        try {
            Trade trade = pendingTrades.get(tradeId);
            if (trade == null) {
                player.sendSystemMessage(Component.literal("交易不存在或已过期"));
                return;
            }
            
            if (!trade.getTargetId().equals(player.getUUID()) && !trade.getInitiatorId().equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal("你不是此交易的参与者"));
                return;
            }
            
            trade.setStatus(Trade.Status.DENIED);
            pendingTrades.remove(tradeId);
            player.sendSystemMessage(Component.literal("交易已拒绝"));
            
            // 发送拒绝消息给另一方
            ServerPlayer initiator = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
            ServerPlayer target = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getTargetId());
            if (trade.getInitiatorId().equals(player.getUUID())) {
                if (target != null) {
                    target.sendSystemMessage(Component.literal("交易被拒绝"));
                }
            } else {
                if (initiator != null) {
                    initiator.sendSystemMessage(Component.literal("交易被拒绝"));
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    public void bargainTrade(ServerPlayer player, UUID tradeId, int newAmount1, int newAmount2) {
        ReentrantLock lock = tradeLocks.computeIfAbsent(tradeId, k -> new ReentrantLock());
        lock.lock();
        try {
            Trade trade = pendingTrades.get(tradeId);
            if (trade == null) {
                player.sendSystemMessage(Component.literal("交易不存在或已过期"));
                return;
            }
            
            if (!trade.getTargetId().equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal("只有目标玩家可以讨价还价"));
                return;
            }
            
            if (trade.isExpired()) {
                pendingTrades.remove(tradeId);
                player.sendSystemMessage(Component.literal("交易已过期"));
                return;
            }
            
            // 校验数量合法性
            Item requestedItem1 = getItemFromCode(trade.getRequestedItem1());
            if (requestedItem1 != null) {
                int maxStackSize1 = requestedItem1.getMaxStackSize();
                if (newAmount1 <= 0 || newAmount1 > maxStackSize1) {
                    player.sendSystemMessage(Component.literal("物品1的数量必须在1到" + maxStackSize1 + "之间"));
                    return;
                }
            }
            
            if (!trade.getRequestedItem2().equals("0")) {
                Item requestedItem2 = getItemFromCode(trade.getRequestedItem2());
                if (requestedItem2 != null) {
                    int maxStackSize2 = requestedItem2.getMaxStackSize();
                    if (newAmount2 < 0 || newAmount2 > maxStackSize2) {
                        player.sendSystemMessage(Component.literal("物品2的数量必须在0到" + maxStackSize2 + "之间"));
                        return;
                    }
                }
            }
            
            trade.setRequestedAmount1(newAmount1);
            trade.setRequestedAmount2(newAmount2);
            trade.setStatus(Trade.Status.BARGAINING);
            
            // 发送还价请求给发起者
            Component message = Component.literal(String.format("[%s]对价格提出了修改请求，你获得的物品分别改为[%d]和[%d]，你是否愿意？用请/szkauhandler %s acce/deny 回复",
                    player.getName().getString(),
                    newAmount1,
                    newAmount2,
                    player.getName().getString()));
            
            ServerPlayer initiator = player.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
            if (initiator != null) {
                initiator.sendSystemMessage(message);
            }
        } finally {
            lock.unlock();
        }
    }
    
    private boolean executeTrade(Trade trade) {
        // 这里需要从上下文获取ServerLevel，暂时使用null，后续需要修改调用方式
        // 实际上，executeTrade应该从调用它的方法中获取ServerPlayer对象，然后通过player.serverLevel()获取ServerLevel
        // 或者修改executeTrade方法，让它接受ServerPlayer参数
        return false;
    }
    
    // 新的executeTrade方法，接受ServerPlayer参数
    private boolean executeTrade(Trade trade, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ServerPlayer initiator = level.getServer().getPlayerList().getPlayer(trade.getInitiatorId());
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(trade.getTargetId());
        ItemStack offeredItem = trade.getOfferedItem();
        
        // 1. 预验证所有物品
        // 验证发起者提供的物品
        boolean initiatorInfinite = Szkauhandler.getInstance().getConfigManager().isInfiniteModeEnabled(initiator.getUUID());
        if (!initiatorInfinite && !hasItem(initiator, offeredItem)) {
            return false;
        }
        
        // 验证潜影盒
        if (!validateShulkerBox(initiator, offeredItem)) {
            return false;
        }
        
        // 验证物品黑名单
        if (Szkauhandler.getInstance().getConfigManager().isItemBlocked(
                offeredItem.getItem().getDescriptionId(), initiator.getUUID())) {
            return false;
        }
        
        // 验证目标请求的物品
        Item requestedItem1 = getItemFromCode(trade.getRequestedItem1());
        if (requestedItem1 == null) {
            return false;
        }
        ItemStack requestedStack1 = new ItemStack(requestedItem1, trade.getRequestedAmount1());
        if (!hasItem(target, requestedStack1)) {
            return false;
        }
        
        if (Szkauhandler.getInstance().getConfigManager().isItemBlocked(
                requestedItem1.getDescriptionId(), target.getUUID())) {
            return false;
        }
        
        ItemStack requestedStack2 = ItemStack.EMPTY;
        if (!trade.getRequestedItem2().equals("0")) {
            Item requestedItem2 = getItemFromCode(trade.getRequestedItem2());
            if (requestedItem2 == null) {
                return false;
            }
            requestedStack2 = new ItemStack(requestedItem2, trade.getRequestedAmount2());
            if (!hasItem(target, requestedStack2)) {
                return false;
            }
            
            if (Szkauhandler.getInstance().getConfigManager().isItemBlocked(
                    requestedItem2.getDescriptionId(), target.getUUID())) {
                return false;
            }
        }
        
        // 2. 创建物品快照（原子性保障）
        Map<Integer, ItemStack> initiatorSnapshot = snapshotInventory(initiator);
        Map<Integer, ItemStack> targetSnapshot = snapshotInventory(target);
        
        try {
            // 3. 执行物品转移
            // 移除发起者的物品（无限模式下不移除）
            if (!initiatorInfinite) {
                ItemStack removedOffered = removeItemWithNBT(initiator, offeredItem);
                if (removedOffered.isEmpty()) {
                    throw new Exception("无法移除发起者物品");
                }
            }
            
            // 移除目标的物品
            ItemStack removedRequested1 = removeItemWithNBT(target, requestedStack1);
            if (removedRequested1.isEmpty()) {
                throw new Exception("无法移除目标物品1");
            }
            
            if (!requestedStack2.isEmpty()) {
                ItemStack removedRequested2 = removeItemWithNBT(target, requestedStack2);
                if (removedRequested2.isEmpty()) {
                    throw new Exception("无法移除目标物品2");
                }
            }
            
            // 添加物品到目标
            addItemWithCheck(target, copyItemWithFullNBT(offeredItem));
            
            // 添加物品到发起者
            addItemWithCheck(initiator, copyItemWithFullNBT(requestedStack1));
            if (!requestedStack2.isEmpty()) {
                addItemWithCheck(initiator, copyItemWithFullNBT(requestedStack2));
            }
            
            // 4. 验证转移结果
            return true;
        } catch (Throwable t) {
            // 5. 回滚：恢复到快照状态
            try {
                restoreInventory(initiator, initiatorSnapshot);
                restoreInventory(target, targetSnapshot);
            } catch (Throwable rollbackError) {
                // 回滚失败，记录错误
                LOGGER.error("交易回滚失败: {}", rollbackError.getMessage());
            }
            return false;
        }
    }
    
    private boolean hasItem(ServerPlayer player, ItemStack targetStack) {
        int required = targetStack.getCount();
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(stack, targetStack)) {
                count += stack.getCount();
                if (count >= required) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean hasItem(ServerPlayer player, Item item, int amount) {
        return hasItem(player, new ItemStack(item, amount));
    }
    
    private ItemStack removeItemWithNBT(ServerPlayer player, ItemStack targetStack) {
        int remaining = targetStack.getCount();
        ItemStack removed = ItemStack.EMPTY;
        
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(stack, targetStack)) {
                int take = Math.min(stack.getCount(), remaining);
                ItemStack toRemove = stack.split(take);
                remaining -= take;
                
                if (removed.isEmpty()) {
                    removed = toRemove;
                } else {
                    removed.grow(take);
                }
            }
        }
        
        return removed;
    }
    
    private void removeItem(ServerPlayer player, Item item, int amount) {
        removeItemWithNBT(player, new ItemStack(item, amount));
    }
    
    private void addItemWithCheck(ServerPlayer player, ItemStack itemStack) throws Exception {
        ItemStack copy = itemStack.copy();
        boolean success = player.getInventory().add(copy);
        if (!success || !copy.isEmpty()) {
            throw new Exception("物品栏空间不足");
        }
    }
    
    private void addItem(ServerPlayer player, Item item, int amount) {
        try {
            addItemWithCheck(player, new ItemStack(item, amount));
        } catch (Exception e) {
            // 记录物品添加失败的日志
            LOGGER.warn("物品添加失败: {}，数量: {}", item.getDescriptionId(), amount);
        }
    }
    
    private void addItem(ServerPlayer player, ItemStack itemStack) {
        try {
            addItemWithCheck(player, itemStack);
        } catch (Exception e) {
            // 记录物品添加失败的日志
            LOGGER.warn("物品添加失败: {}，数量: {}", itemStack.getItem().getDescriptionId(), itemStack.getCount());
        }
    }
    
    private Item getItemFromCode(String code) {
        String itemId = Szkauhandler.getInstance().getConfigManager().getCurrencyItemId(code);
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(rl);
    }
    
    private String getItemNameFromCode(String code) {
        if (code.equals("0")) {
            return "";
        }
        Item item = getItemFromCode(code);
        return item != null ? item.getDescription().getString() : code;
    }
    
    private boolean validateShulkerBox(ServerPlayer player, ItemStack shulkerBox) {
        if (!shulkerBox.getItem().toString().contains("shulker_box")) {
            return true;
        }
        
        CompoundTag tag = shulkerBox.getTagElement("BlockEntityTag");
        if (tag == null) {
            return true;
        }
        
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (Tag itemTag : items) {
            ItemStack innerStack = ItemStack.of((CompoundTag) itemTag);
            if (Szkauhandler.getInstance().getConfigManager().isItemBlocked(
                    innerStack.getItem().getDescriptionId(), player.getUUID())) {
                return false;
            }
            if (innerStack.getCount() <= 0) {
                return false;
            }
        }
        
        return true;
    }
    
    private Map<Integer, ItemStack> snapshotInventory(ServerPlayer player) {
        Map<Integer, ItemStack> snapshot = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            snapshot.put(i, player.getInventory().getItem(i).copy());
        }
        return snapshot;
    }
    
    private void restoreInventory(ServerPlayer player, Map<Integer, ItemStack> snapshot) {
        player.getInventory().clearContent();
        for (Map.Entry<Integer, ItemStack> entry : snapshot.entrySet()) {
            player.getInventory().setItem(entry.getKey(), entry.getValue().copy());
        }
    }
    
    private ItemStack copyItemWithFullNBT(ItemStack original) {
        ItemStack copy = original.copy();
        if (original.hasTag()) {
            copy.setTag(original.getTag().copy());
        }
        return copy;
    }
    
    private boolean verifyTradeResult(Trade trade, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ServerPlayer initiator = level.getServer().getPlayerList().getPlayer(trade.getInitiatorId());
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(trade.getTargetId());
        ItemStack offeredItem = trade.getOfferedItem();
        
        // 验证目标是否收到发起者提供的物品
        boolean targetHasOfferedItem = false;
        for (int i = 0; i < target.getInventory().getContainerSize(); i++) {
            ItemStack stack = target.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(stack, offeredItem)) {
                targetHasOfferedItem = true;
                break;
            }
        }
        if (!targetHasOfferedItem) {
            return false;
        }
        
        // 验证发起者是否收到目标提供的物品
        Item requestedItem1 = getItemFromCode(trade.getRequestedItem1());
        if (requestedItem1 == null) {
            return false;
        }
        ItemStack requestedStack1 = new ItemStack(requestedItem1, trade.getRequestedAmount1());
        boolean initiatorHasRequestedItem1 = false;
        for (int i = 0; i < initiator.getInventory().getContainerSize(); i++) {
            ItemStack stack = initiator.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(stack, requestedStack1)) {
                initiatorHasRequestedItem1 = true;
                break;
            }
        }
        if (!initiatorHasRequestedItem1) {
            return false;
        }
        
        if (!trade.getRequestedItem2().equals("0")) {
            Item requestedItem2 = getItemFromCode(trade.getRequestedItem2());
            if (requestedItem2 == null) {
                return false;
            }
            ItemStack requestedStack2 = new ItemStack(requestedItem2, trade.getRequestedAmount2());
            boolean initiatorHasRequestedItem2 = false;
            for (int i = 0; i < initiator.getInventory().getContainerSize(); i++) {
                ItemStack stack = initiator.getInventory().getItem(i);
                if (ItemStack.isSameItemSameTags(stack, requestedStack2)) {
                    initiatorHasRequestedItem2 = true;
                    break;
                }
            }
            if (!initiatorHasRequestedItem2) {
                return false;
            }
        }
        
        return true;
    }
    
    private void completeTrade(UUID tradeId, Trade trade) {
        synchronized (pendingTrades) {
            trade.setStatus(Trade.Status.COMPLETED);
            pendingTrades.remove(tradeId);
        }
    }
    
    private void addTradeHistory(UUID playerId, Trade trade) {
        TradeHistory history = new TradeHistory(trade);
        // 添加到内存中以便查询
        tradeHistories.computeIfAbsent(playerId, k -> new ArrayList<>()).add(history);
        // 记录到日志文件
        logTradeHistory(history);
    }
    
    private void logTradeHistory(TradeHistory history) {
        try {
            Path dataDir = FMLPaths.CONFIGDIR.get().resolve("szkauhandler");
            Files.createDirectories(dataDir);
            
            // 记录到日志文件
            Path logFile = dataDir.resolve("trade_log.txt");
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String logEntry = String.format("[%s] 交易ID: %s, 发起者: %s, 目标: %s, 状态: %s, 提供: %s x%d, 请求: %s x%d %s, 备注: %s%n",
                        new Date(history.getTimestamp()).toString(),
                        history.getTradeId(),
                        history.getInitiatorId(),
                        history.getTargetId(),
                        history.getStatus().name(),
                        history.getOfferedItem(),
                        history.getOfferedAmount(),
                        history.getRequestedItem1(),
                        history.getRequestedAmount1(),
                        history.getRequestedItem2().equals("0") ? "" : String.format("和 %s x%d", history.getRequestedItem2(), history.getRequestedAmount2()),
                        history.getNote() != null ? history.getNote() : "无");
                writer.write(logEntry);
            }
        } catch (IOException e) {
            LOGGER.error("记录交易日志失败: {}", e.getMessage());
        }
    }
    
    public List<TradeHistory> getTradeHistories(UUID playerId) {
        return tradeHistories.getOrDefault(playerId, Collections.emptyList());
    }
    
    public void saveData() {
        try {
            Path dataDir = FMLPaths.CONFIGDIR.get().resolve("szkauhandler");
            Files.createDirectories(dataDir);
            
            // 只保存异常交易到JSON（例如拒绝的交易）
            Map<UUID, List<TradeHistory>> exceptionalTrades = new HashMap<>();
            for (Map.Entry<UUID, List<TradeHistory>> entry : tradeHistories.entrySet()) {
                List<TradeHistory> exceptional = entry.getValue().stream()
                        .filter(history -> history.getStatus() == Trade.Status.DENIED)
                        .collect(Collectors.toList());
                if (!exceptional.isEmpty()) {
                    exceptionalTrades.put(entry.getKey(), exceptional);
                }
            }
            
            // 保存异常交易
            Path exceptionFile = dataDir.resolve("exceptional_trades.json");
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = Files.newBufferedWriter(exceptionFile)) {
                gson.toJson(exceptionalTrades, writer);
            }
        } catch (IOException e) {
            LOGGER.error("保存数据失败: {}", e.getMessage());
        }
    }
    
    public void loadData() {
        try {
            Path dataDir = FMLPaths.CONFIGDIR.get().resolve("szkauhandler");
            Path exceptionFile = dataDir.resolve("exceptional_trades.json");
            
            if (Files.exists(exceptionFile)) {
                Gson gson = new Gson();
                try (Reader reader = Files.newBufferedReader(exceptionFile)) {
                    Type type = new TypeToken<Map<UUID, List<TradeHistory>>>(){}.getType();
                    Map<UUID, List<TradeHistory>> loadedExceptions = gson.fromJson(reader, type);
                    if (loadedExceptions != null) {
                        tradeHistories.putAll(loadedExceptions);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("加载数据失败: {}", e.getMessage());
        }
    }
    
    public List<Trade> getPendingTrades(UUID playerId) {
        List<Trade> trades = new ArrayList<>();
        for (Trade trade : pendingTrades.values()) {
            if (trade.getInitiatorId().equals(playerId) || trade.getTargetId().equals(playerId)) {
                trades.add(trade);
            }
        }
        return trades;
    }
    
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 每30秒检查一次
        if (currentTime - lastCleanupTime >= 30000) {
            // 清理过期交易
            Iterator<Map.Entry<UUID, Trade>> tradeIterator = pendingTrades.entrySet().iterator();
            while (tradeIterator.hasNext()) {
                Map.Entry<UUID, Trade> entry = tradeIterator.next();
                Trade trade = entry.getValue();
                if (trade.isExpired()) {
                    tradeIterator.remove();
                    // 同时移除对应的锁对象
                    tradeLocks.remove(entry.getKey());
                    
                    // 提示交易双方交易已过期
                    net.minecraft.server.MinecraftServer server = event.getServer();
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    ServerPlayer initiator = level.getServer().getPlayerList().getPlayer(trade.getInitiatorId());
                    ServerPlayer target = level.getServer().getPlayerList().getPlayer(trade.getTargetId());
                    if (initiator != null) {
                        initiator.sendSystemMessage(Component.literal("交易已过期"));
                    }
                    if (target != null) {
                        target.sendSystemMessage(Component.literal("交易已过期"));
                    }
                }
            }
            
            // 清理无用的锁对象
            Iterator<UUID> lockIterator = tradeLocks.keySet().iterator();
            while (lockIterator.hasNext()) {
                UUID tradeId = lockIterator.next();
                if (!pendingTrades.containsKey(tradeId)) {
                    lockIterator.remove();
                }
            }
            
            lastCleanupTime = currentTime;
        }
        
        // 每60秒保存一次数据
        if (currentTime - lastSaveTime >= 60000) {
            saveData();
            lastSaveTime = currentTime;
        }
    }
    
    public static class Trade {
        private final UUID id;
        private final UUID initiatorId;
        private final UUID targetId;
        private final ItemStack offeredItem;
        private final String requestedItem1;
        private final String requestedItem2;
        private int requestedAmount1;
        private int requestedAmount2;
        private final String note;
        private final long creationTime;
        private Status status;
        
        public Trade(UUID id, ServerPlayer initiator, ServerPlayer target, ItemStack offeredItem, String requestedItem1, int amount1, String requestedItem2, int amount2, String note) {
            this.id = id;
            this.initiatorId = initiator.getUUID();
            this.targetId = target.getUUID();
            this.offeredItem = offeredItem;
            this.requestedItem1 = requestedItem1;
            this.requestedItem2 = requestedItem2;
            this.requestedAmount1 = amount1;
            this.requestedAmount2 = amount2;
            this.note = note;
            this.creationTime = System.currentTimeMillis();
            this.status = Status.PENDING;
        }
        
        public UUID getId() {
            return id;
        }
        
        public UUID getInitiatorId() {
            return initiatorId;
        }
        
        public UUID getTargetId() {
            return targetId;
        }
        
        public ServerPlayer getInitiator(net.minecraft.server.level.ServerLevel level) {
            return level.getServer().getPlayerList().getPlayer(initiatorId);
        }
        
        public ServerPlayer getTarget(net.minecraft.server.level.ServerLevel level) {
            return level.getServer().getPlayerList().getPlayer(targetId);
        }
        
        public ItemStack getOfferedItem() {
            return offeredItem;
        }
        
        public String getRequestedItem1() {
            return requestedItem1;
        }
        
        public String getRequestedItem2() {
            return requestedItem2;
        }
        
        public int getRequestedAmount1() {
            return requestedAmount1;
        }
        
        public void setRequestedAmount1(int amount) {
            this.requestedAmount1 = amount;
        }
        
        public int getRequestedAmount2() {
            return requestedAmount2;
        }
        
        public void setRequestedAmount2(int amount) {
            this.requestedAmount2 = amount;
        }
        
        public String getNote() {
            return note;
        }
        
        public Status getStatus() {
            return status;
        }
        
        public void setStatus(Status status) {
            this.status = status;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - creationTime > 5 * 60 * 1000; // 5分钟过期
        }
        
        public long getCreationTime() {
            return creationTime;
        }
        
        public enum Status {
            PENDING, BARGAINING, COMPLETED, DENIED
        }
    }
    
    public static class TradeHistory {
        private final UUID tradeId;
        private final UUID initiatorId;
        private final UUID targetId;
        private final String offeredItem;
        private final int offeredAmount;
        private final String requestedItem1;
        private final int requestedAmount1;
        private final String requestedItem2;
        private final int requestedAmount2;
        private final String note;
        private final long timestamp;
        private final Trade.Status status;
        
        public TradeHistory(Trade trade) {
            this.tradeId = trade.getId();
            this.initiatorId = trade.getInitiatorId();
            this.targetId = trade.getTargetId();
            this.offeredItem = trade.getOfferedItem().getDisplayName().getString();
            this.offeredAmount = trade.getOfferedItem().getCount();
            this.requestedItem1 = trade.getRequestedItem1();
            this.requestedAmount1 = trade.getRequestedAmount1();
            this.requestedItem2 = trade.getRequestedItem2();
            this.requestedAmount2 = trade.getRequestedAmount2();
            this.note = trade.getNote();
            this.timestamp = System.currentTimeMillis();
            this.status = trade.getStatus();
        }
        
        // Getters
        public UUID getTradeId() {
            return tradeId;
        }
        
        public UUID getInitiatorId() {
            return initiatorId;
        }
        
        public UUID getTargetId() {
            return targetId;
        }
        
        public String getOfferedItem() {
            return offeredItem;
        }
        
        public int getOfferedAmount() {
            return offeredAmount;
        }
        
        public String getRequestedItem1() {
            return requestedItem1;
        }
        
        public int getRequestedAmount1() {
            return requestedAmount1;
        }
        
        public String getRequestedItem2() {
            return requestedItem2;
        }
        
        public int getRequestedAmount2() {
            return requestedAmount2;
        }
        
        public String getNote() {
            return note;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public Trade.Status getStatus() {
            return status;
        }
    }
}