package dev.xlonia.szkauhandler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

public class SzkauhandlerCommands {
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("szkauhandler")
                .then(Commands.literal("trad")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("item1", StringArgumentType.string())
                                        .then(Commands.argument("amount1", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("item2", StringArgumentType.string())
                                                        .then(Commands.argument("amount2", IntegerArgumentType.integer(0))
                                                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                                                        .executes(context -> {
                                                                            ServerPlayer initiator = context.getSource().getPlayerOrException();
                                                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                                            String item1 = StringArgumentType.getString(context, "item1");
                                                                            int amount1 = IntegerArgumentType.getInteger(context, "amount1");
                                                                            String item2 = StringArgumentType.getString(context, "item2");
                                                                            int amount2 = IntegerArgumentType.getInteger(context, "amount2");
                                                                            String note = StringArgumentType.getString(context, "note");
                                                                            return createTrade(initiator, target, item1, amount1, item2, amount2, note);
                                                                        })
                                                                )
                                                                .executes(context -> {
                                                                    ServerPlayer initiator = context.getSource().getPlayerOrException();
                                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                                    String item1 = StringArgumentType.getString(context, "item1");
                                                                    int amount1 = IntegerArgumentType.getInteger(context, "amount1");
                                                                    String item2 = StringArgumentType.getString(context, "item2");
                                                                    int amount2 = IntegerArgumentType.getInteger(context, "amount2");
                                                                    return createTrade(initiator, target, item1, amount1, item2, amount2, null);
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("acce")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    return acceptTrade(player, target);
                                })
                        )
                )
                .then(Commands.literal("deny")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    return denyTrade(player, target);
                                })
                        )
                )
                .then(Commands.literal("barg")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount1", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("amount2", IntegerArgumentType.integer(0))
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    int amount1 = IntegerArgumentType.getInteger(context, "amount1");
                                                    int amount2 = IntegerArgumentType.getInteger(context, "amount2");
                                                    return bargainTrade(player, target, amount1, amount2);
                                                })
                                        )
                                )
                        )
                )
                .then(Commands.literal("help")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            return showHelp(player);
                        })
                )
                .then(Commands.literal("marklist")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            return showMarkList(player);
                        })
                )
        );
    }
    
    private static int createTrade(ServerPlayer initiator, ServerPlayer target, String item1, int amount1, String item2, int amount2, String note) {
        // 检查发起者副手是否有物品
        ItemStack offeredItem = initiator.getOffhandItem();
        if (offeredItem.isEmpty()) {
            initiator.sendSystemMessage(Component.literal("副手必须持有要交易的物品"));
            return 0;
        }
        
        // 检查目标是否在线
        if (!target.isAlive() || !target.serverLevel().players().contains(target)) {
            initiator.sendSystemMessage(Component.literal("目标玩家不在线"));
            return 0;
        }
        
        // 检查发起者是否被禁言
        if (Szkauhandler.getInstance().getConfigManager().isPlayerBanned(initiator.getUUID())) {
            initiator.sendSystemMessage(Component.literal("你已被禁止交易"));
            return 0;
        }
        
        // 检查目标是否被禁言
        if (Szkauhandler.getInstance().getConfigManager().isPlayerBanned(target.getUUID())) {
            initiator.sendSystemMessage(Component.literal("目标玩家已被禁止交易"));
            return 0;
        }
        
        // 检查物品黑名单
        if (Szkauhandler.getInstance().getConfigManager().isItemBlocked(
                offeredItem.getItem().getDescriptionId(), initiator.getUUID())) {
            initiator.sendSystemMessage(Component.literal("你提供的物品已被禁止交易"));
            return 0;
        }
        
        // 创建交易
        Szkauhandler.getInstance().getTradeManager().createTrade(initiator, target, offeredItem, item1, amount1, item2, amount2, note);
        initiator.sendSystemMessage(Component.literal("交易请求已发送"));
        return 1;
    }
    
    private static int acceptTrade(ServerPlayer player, ServerPlayer target) {
        // 查找与目标相关的待处理交易
        UUID tradeId = findPendingTradeId(player, target);
        if (tradeId == null) {
            player.sendSystemMessage(Component.literal("没有找到与该玩家的待处理交易"));
            return 0;
        }
        
        Szkauhandler.getInstance().getTradeManager().acceptTrade(player, tradeId);
        return 1;
    }
    
    private static int denyTrade(ServerPlayer player, ServerPlayer target) {
        // 查找与目标相关的待处理交易
        UUID tradeId = findPendingTradeId(player, target);
        if (tradeId == null) {
            player.sendSystemMessage(Component.literal("没有找到与该玩家的待处理交易"));
            return 0;
        }
        
        Szkauhandler.getInstance().getTradeManager().denyTrade(player, tradeId);
        return 1;
    }
    
    private static int bargainTrade(ServerPlayer player, ServerPlayer target, int amount1, int amount2) {
        // 查找与目标相关的待处理交易
        UUID tradeId = findPendingTradeId(player, target);
        if (tradeId == null) {
            player.sendSystemMessage(Component.literal("没有找到与该玩家的待处理交易"));
            return 0;
        }
        
        Szkauhandler.getInstance().getTradeManager().bargainTrade(player, tradeId, amount1, amount2);
        return 1;
    }
    
    private static int showHelp(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Szkauhandler 命令帮助："));
        player.sendSystemMessage(Component.literal("/szkauhandler trad <玩家> <物品1> <数量1> <物品2> <数量2> [备注] - 发起交易"));
        player.sendSystemMessage(Component.literal("/szkauhandler acce <玩家> - 接受交易"));
        player.sendSystemMessage(Component.literal("/szkauhandler deny <玩家> - 拒绝交易"));
        player.sendSystemMessage(Component.literal("/szkauhandler barg <玩家> <数量1> <数量2> - 讨价还价"));
        player.sendSystemMessage(Component.literal("/szkauhandler help - 显示此帮助"));
        player.sendSystemMessage(Component.literal("/szkauhandler marklist - 显示交易简码列表"));
        return 1;
    }
    
    private static int showMarkList(ServerPlayer player) {
        StringBuilder message = new StringBuilder("目前此服务器的交易简码为\n|原名 物品ID 简码\n");
        
        Map<String, String> customCurrencies = Szkauhandler.getInstance().getConfigManager().getCustomCurrencies();
        for (Map.Entry<String, String> entry : customCurrencies.entrySet()) {
            String code = entry.getKey();
            String itemId = entry.getValue();
            
            // 尝试获取物品的本地化名称
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            String itemName = itemId;
            if (rl != null) {
                net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (item != null) {
                    itemName = item.getDescription().getString();
                }
            }
            
            message.append("|").append(itemName).append(" " ).append(itemId).append(" " ).append(code).append("\n" );
        }
        
        player.sendSystemMessage(Component.literal(message.toString()));
        return 1;
    }
    
    private static UUID findPendingTradeId(ServerPlayer player, ServerPlayer target) {
        // 查找最新的待处理交易
        UUID latestTradeId = null;
        long latestTime = 0;
        
        for (TradeManager.Trade trade : Szkauhandler.getInstance().getTradeManager().getPendingTrades(player.getUUID())) {
            if ((trade.getInitiatorId().equals(target.getUUID()) && trade.getTargetId().equals(player.getUUID())) ||
                (trade.getInitiatorId().equals(player.getUUID()) && trade.getTargetId().equals(target.getUUID()))) {
                if (trade.getCreationTime() > latestTime) {
                    latestTime = trade.getCreationTime();
                    latestTradeId = trade.getId();
                }
            }
        }
        return latestTradeId;
    }
}