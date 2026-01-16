package com.example.addon.modules;

import com.example.addon.RealTime;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

public class Realtime extends Module {
    public Realtime() {
        super(RealTime.CATEGORY, "Real-Time", "Syncs the world time with your system time.");
    }
    long oldTime;

    @Override
    public void onActivate() {
        oldTime = mc.world.getTime();
    }

    @Override
    public void onDeactivate() {
        mc.world.getLevelProperties().setTimeOfDay(oldTime);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof WorldTimeUpdateS2CPacket) {
            oldTime = ((WorldTimeUpdateS2CPacket) event.packet).timeOfDay();
            event.cancel();
        }
    }

    long millisSinceMidnight;

    @EventHandler
    private void onTick(TickEvent.Post event) {
        millisSinceMidnight = java.time.LocalTime.now().toNanoOfDay() / 1_000_000L;
        long secondsSinceMidnight = millisSinceMidnight / 1000;
        long minecraftTime = (long) ((secondsSinceMidnight / 86400.0) * 24000 + 18000) % 24000;
        mc.world.getLevelProperties().setTimeOfDay(minecraftTime);
    }



}
