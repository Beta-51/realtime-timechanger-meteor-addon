package com.example.addon.modules;

import com.example.addon.RealTime;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Realtime extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLocation = settings.createGroup("Location");

    private final Setting<Boolean> useSunriseSunset = sgGeneral.add(new BoolSetting.Builder()
        .name("use-sunrise-sunset")
        .description("Use actual sunrise/sunset times based on your location.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> latitude = sgLocation.add(new DoubleSetting.Builder()
        .name("latitude")
        .description("Latitude to get accurate sunrise/sunset times.")
        .defaultValue(36.1628)
        .min(-90)
        .max(90)
        .sliderMin(-90)
        .sliderMax(90)
        .build()
    );

    private final Setting<Double> longitude = sgLocation.add(new DoubleSetting.Builder()
        .name("longitude")
        .description("Longitude to get accurate sunrise/sunset times.")
        .defaultValue(-85.5016)
        .min(-180)
        .max(180)
        .sliderMin(-180)
        .sliderMax(180)
        .build()
    );

    public Realtime() {
        super(RealTime.CATEGORY, "Real-Time", "Syncs the world time with your system time.");
    }
    long oldTime;

    // Sunrise/Sunset cache
    private LocalDate cachedDate = null;
    private long sunriseSeconds = -1;
    private long sunsetSeconds = -1;
    private long solarNoonSeconds = -1;

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

        long minecraftTime;
        if (useSunriseSunset.get()) {
            minecraftTime = calculateMinecraftTimeWithSunriseSunset(secondsSinceMidnight);
        } else {
            // Original linear mapping
            minecraftTime = (long) ((secondsSinceMidnight / 86400.0) * 24000 + 18000) % 24000;
        }

        mc.world.getLevelProperties().setTimeOfDay(minecraftTime);
    }

    private long calculateMinecraftTimeWithSunriseSunset(long secondsSinceMidnight) {
        // Update cache if needed
        LocalDate today = LocalDate.now();
        if (cachedDate == null || !cachedDate.equals(today)) {
            fetchSunriseSunsetData();
        }

        // Fallback to linear mapping if API failed
        if (sunriseSeconds == -1 || sunsetSeconds == -1) {
            return (long) ((secondsSinceMidnight / 86400.0) * 24000 + 18000) % 24000;
        }

        // Minecraft time mapping:
        // 0 = 6:00 AM (sunrise)
        // 6000 = 12:00 PM (noon)
        // 12000 = 6:00 PM (sunset)
        // 18000 = 12:00 AM (midnight)

        long minecraftTime;

        if (secondsSinceMidnight < sunriseSeconds) {
            // Before sunrise: night time (18000 to 24000)
            // Map from midnight to sunrise -> 18000 to 24000 (0)
            double progress = (double) secondsSinceMidnight / sunriseSeconds;
            minecraftTime = (long) (18000 + progress * 6000);
        } else if (secondsSinceMidnight < solarNoonSeconds) {
            // Morning: sunrise to noon
            // Map from sunrise to solar noon -> 0 to 6000
            double progress = (double) (secondsSinceMidnight - sunriseSeconds) / (solarNoonSeconds - sunriseSeconds);
            minecraftTime = (long) (progress * 6000);
        } else if (secondsSinceMidnight < sunsetSeconds) {
            // Afternoon: noon to sunset
            // Map from solar noon to sunset -> 6000 to 12000
            double progress = (double) (secondsSinceMidnight - solarNoonSeconds) / (sunsetSeconds - solarNoonSeconds);
            minecraftTime = (long) (6000 + progress * 6000);
        } else {
            // After sunset: evening/night
            // Map from sunset to midnight -> 12000 to 18000
            double progress = (double) (secondsSinceMidnight - sunsetSeconds) / (86400 - sunsetSeconds);
            minecraftTime = (long) (12000 + progress * 6000);
        }

        return minecraftTime % 24000;
    }

    private void fetchSunriseSunsetData() {
        try {
            String apiUrl = String.format(
                "https://api.sunrise-sunset.org/json?lat=%.6f&lng=%.6f&formatted=0&date=today",
                latitude.get(),
                longitude.get()
            );

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String json = response.toString();
            String sunrise = extractJsonValue(json, "sunrise");
            String sunset = extractJsonValue(json, "sunset");
            String solarNoon = extractJsonValue(json, "solar_noon");

            if (sunrise != null && sunset != null && solarNoon != null) {
                // Parse ISO 8601 datetime and extract time in seconds since midnight
                sunriseSeconds = parseTimeToSecondsOfDay(sunrise);
                sunsetSeconds = parseTimeToSecondsOfDay(sunset);
                solarNoonSeconds = parseTimeToSecondsOfDay(solarNoon);
                cachedDate = LocalDate.now();

                info("Sunrise/sunset data updated for " + cachedDate);
            }
        } catch (Exception e) {
            error("Failed to fetch sunrise/sunset data: " + e.getMessage());
            // Keep cached data or use -1 to fall back to linear mapping
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return null;

        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return null;

        return json.substring(startIndex, endIndex);
    }

    private long parseTimeToSecondsOfDay(String isoDateTime) {
        try {
            // "2025-01-16T05:30:00+00:00"
            ZonedDateTime zdt = ZonedDateTime.parse(isoDateTime, DateTimeFormatter.ISO_DATE_TIME);
            java.time.LocalTime localTime = zdt.withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime();

            return localTime.toSecondOfDay();
        } catch (Exception e) {
            error("Failed to parse time: " + e.getMessage());
            return -1;
        }
    }



}
