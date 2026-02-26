package dev.xlonia.szkauhandler;

import net.minecraft.network.chat.Component;

public class I18n {
    public static Component translate(String key, Object... args) {
        return Component.translatable(key, args);
    }
}