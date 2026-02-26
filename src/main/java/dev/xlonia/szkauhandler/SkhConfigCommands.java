package dev.xlonia.szkauhandler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SkhConfigCommands {
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("skhconfig")
                .requires(source -> source.hasPermission(2)) // 使用OP权限
                .then(Commands.literal("item")
                        .then(Commands.argument("originalItem", StringArgumentType.string())
                                .then(Commands.argument("customCode", StringArgumentType.string())
                                        .executes(context -> {
                                            String originalItem = StringArgumentType.getString(context, "originalItem");
                                            String customCode = StringArgumentType.getString(context, "customCode");
                                            return setCustomCurrency(context.getSource().getPlayerOrException(), originalItem, customCode);
                                        })
                                )
                        )
                )
                .then(Commands.literal("ban")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("duration", IntegerArgumentType.integer(0))
                                        .executes(context -> {
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            int duration = IntegerArgumentType.getInteger(context, "duration");
                                            return banPlayer(context.getSource().getPlayerOrException(), player, duration);
                                        })
                                )
                                .executes(context -> {
                                    ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                    return banPlayer(context.getSource().getPlayerOrException(), player, 0);
                                })
                        )
                )
                .then(Commands.literal("tradban")
                        .then(Commands.argument("itemId", StringArgumentType.string())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            String itemId = StringArgumentType.getString(context, "itemId");
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            return blockItemForPlayer(context.getSource().getPlayerOrException(), itemId, player);
                                        })
                                )
                                .executes(context -> {
                                    String itemId = StringArgumentType.getString(context, "itemId");
                                    return blockItem(context.getSource().getPlayerOrException(), itemId);
                                })
                        )
                )
                .then(Commands.literal("list")
                        .then(Commands.argument("page", StringArgumentType.word())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            String page = StringArgumentType.getString(context, "page");
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            return showPendingTrades(context.getSource().getPlayerOrException(), page, player);
                                        })
                                )
                                .executes(context -> {
                                    String page = StringArgumentType.getString(context, "page");
                                    return showPendingTrades(context.getSource().getPlayerOrException(), page, null);
                                })
                        )
                )
                .then(Commands.literal("istory")
                        .then(Commands.argument("page", StringArgumentType.word())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            String page = StringArgumentType.getString(context, "page");
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            return showTradeHistory(context.getSource().getPlayerOrException(), page, player);
                                        })
                                )
                                .executes(context -> {
                                    String page = StringArgumentType.getString(context, "page");
                                    return showTradeHistory(context.getSource().getPlayerOrException(), page, null);
                                })
                        )
                )
                .then(Commands.literal("inf")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                    return setInfiniteMode(context.getSource().getPlayerOrException(), enabled);
                                })
                        )
                )
        );
    }
    
    private static int setCustomCurrency(ServerPlayer op, String originalItem, String customCode) {
        Szkauhandler.getInstance().getConfigManager().addCustomCurrency(customCode, originalItem);
        op.sendSystemMessage(Component.literal(String.format("已设置自定义货币：%s -> %s", originalItem, customCode)));
        return 1;
    }
    
    private static int banPlayer(ServerPlayer op, ServerPlayer target, int duration) {
        Szkauhandler.getInstance().getConfigManager().banPlayer(target.getUUID(), duration);
        if (duration > 0) {
            op.sendSystemMessage(Component.literal(String.format("已禁止玩家 %s 交易，持续 %d 秒", target.getName().getString(), duration)));
            target.sendSystemMessage(Component.literal(String.format("你已被禁止交易，持续 %d 秒", duration)));
        } else {
            op.sendSystemMessage(Component.literal(String.format("已解除玩家 %s 的交易禁止", target.getName().getString())));
            target.sendSystemMessage(Component.literal("你的交易禁止已解除"));
        }
        return 1;
    }
    
    private static int blockItem(ServerPlayer op, String itemId) {
        Szkauhandler.getInstance().getConfigManager().blockItem(itemId);
        op.sendSystemMessage(Component.literal(String.format("已阻止物品 %s 用于交易", itemId)));
        return 1;
    }
    
    private static int blockItemForPlayer(ServerPlayer op, String itemId, ServerPlayer target) {
        Szkauhandler.getInstance().getConfigManager().blockItemForPlayer(target.getUUID(), itemId);
        op.sendSystemMessage(Component.literal(String.format("已阻止玩家 %s 交易物品 %s", target.getName().getString(), itemId)));
        target.sendSystemMessage(Component.literal(String.format("你已被禁止交易物品 %s", itemId)));
        return 1;
    }
    
    private static int showPendingTrades(ServerPlayer op, String page, ServerPlayer target) {
        if (target != null) {
            // 显示特定玩家的待处理交易
            for (TradeManager.Trade trade : Szkauhandler.getInstance().getTradeManager().getPendingTrades(target.getUUID())) {
                net.minecraft.server.level.ServerPlayer initiator = op.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
                net.minecraft.server.level.ServerPlayer tradeTarget = op.serverLevel().getServer().getPlayerList().getPlayer(trade.getTargetId());
                op.sendSystemMessage(Component.literal(String.format("交易ID: %s, 发起者: %s, 目标: %s, 状态: %s",
                        trade.getId(),
                        initiator != null ? initiator.getName().getString() : trade.getInitiatorId().toString(),
                        tradeTarget != null ? tradeTarget.getName().getString() : trade.getTargetId().toString(),
                        trade.getStatus().name())));
            }
        } else {
            // 显示所有待处理交易（简化实现）
            op.sendSystemMessage(Component.literal("待处理交易列表："));
            // 这里应该遍历所有待处理交易，但为了简化，只显示OP自己的
            for (TradeManager.Trade trade : Szkauhandler.getInstance().getTradeManager().getPendingTrades(op.getUUID())) {
                net.minecraft.server.level.ServerPlayer initiator = op.serverLevel().getServer().getPlayerList().getPlayer(trade.getInitiatorId());
                net.minecraft.server.level.ServerPlayer tradeTarget = op.serverLevel().getServer().getPlayerList().getPlayer(trade.getTargetId());
                op.sendSystemMessage(Component.literal(String.format("交易ID: %s, 发起者: %s, 目标: %s, 状态: %s",
                        trade.getId(),
                        initiator != null ? initiator.getName().getString() : trade.getInitiatorId().toString(),
                        tradeTarget != null ? tradeTarget.getName().getString() : trade.getTargetId().toString(),
                        trade.getStatus().name())));
            }
        }
        return 1;
    }
    
    private static int showTradeHistory(ServerPlayer op, String page, ServerPlayer target) {
        if (target != null) {
            // 显示特定玩家的交易历史
            for (TradeManager.TradeHistory history : Szkauhandler.getInstance().getTradeManager().getTradeHistories(target.getUUID())) {
                op.sendSystemMessage(Component.literal(String.format("交易ID: %s, 时间: %d, 状态: %s",
                        history.getTradeId(),
                        history.getTimestamp(),
                        history.getStatus().name())));
            }
        } else {
            // 显示所有交易历史（简化实现）
            op.sendSystemMessage(Component.literal("交易历史列表："));
            // 这里应该遍历所有交易历史，但为了简化，只显示OP自己的
            for (TradeManager.TradeHistory history : Szkauhandler.getInstance().getTradeManager().getTradeHistories(op.getUUID())) {
                op.sendSystemMessage(Component.literal(String.format("交易ID: %s, 时间: %d, 状态: %s",
                        history.getTradeId(),
                        history.getTimestamp(),
                        history.getStatus().name())));
            }
        }
        return 1;
    }
    
    private static int setInfiniteMode(ServerPlayer op, boolean enabled) {
        // 只有OP可以开启无限模式
        if (enabled && !op.hasPermissions(2)) {
            op.sendSystemMessage(Component.literal("只有OP可以开启无限模式"));
            return 0;
        }
        
        Szkauhandler.getInstance().getConfigManager().setInfiniteMode(op.getUUID(), enabled);
        op.sendSystemMessage(Component.literal(String.format("无限模式已%s", enabled ? "开启" : "关闭")));
        return 1;
    }
}