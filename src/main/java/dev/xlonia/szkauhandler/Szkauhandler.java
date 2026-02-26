package dev.xlonia.szkauhandler;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("szkauhandler")
public class Szkauhandler {
    public static final String MOD_ID = "szkauhandler";
    private static Szkauhandler instance;
    private final TradeManager tradeManager;
    private final ConfigManager configManager;
    
    public Szkauhandler() {
        instance = this;
        tradeManager = new TradeManager();
        configManager = new ConfigManager();
        
        // 加载数据
        configManager.loadConfig();
        tradeManager.loadData();
        
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(tradeManager);
    }
    
    public static Szkauhandler getInstance() {
        return instance;
    }
    
    public TradeManager getTradeManager() {
        return tradeManager;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public void onServerStarting(ServerStartingEvent event) {
        SzkauhandlerCommands.registerCommands(event.getServer().getCommands().getDispatcher());
        SkhConfigCommands.registerCommands(event.getServer().getCommands().getDispatcher());
    }
    
    public void onServerStopping(ServerStoppingEvent event) {
        // 保存数据
        configManager.saveConfig();
        tradeManager.saveData();
    }
}