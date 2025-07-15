package com.cdpyx.chatanit;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.google.inject.Inject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.velocitypowered.api.proxy.Player;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(id = "chatfilter", name = "ChatFilter", version = "1.0", authors = {"cdpyx"})
public class ChatFilterPlugin {
    private final ProxyServer server;
    private Set<String> prohibitedWords = Set.of();
    private final Map<String, String> ipCityCache = new ConcurrentHashMap<>();
    private final Logger logger;

    @Inject
    public ChatFilterPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        fetchProhibitedWords();
    }

    private void fetchProhibitedWords() {
        CompletableFuture.runAsync(() -> {
            try {
                var client = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(new java.net.URI("https://uapis.cn/api/prohibited"))
                        .GET()
                        .build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                var json = new org.json.JSONObject(response.body());
                var arr = json.getJSONArray("data");
                Set<String> words = new java.util.HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    words.add(arr.getString(i));
                }
                this.prohibitedWords = words;
                server.getConsoleCommandSource().sendMessage(
                    Component.text("敏感词列表已加载，共" + words.size() + "个。")
                );
            } catch (Exception e) {
                server.getConsoleCommandSource().sendMessage(
                    Component.text("敏感词加载失败: " + e.getMessage())
                );
            }
        });
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        logger.info("[ChatFilter] PlayerChatEvent triggered: rawMessage='{}', player='{}'", event.getMessage(), event.getPlayer().getUsername());
        String message = event.getMessage();
        String filtered = filterMessage(message);
        String ip = getPlayerIp(event.getPlayer());
        if (ip == null) {
            logger.warn("[ChatFilter] IP 获取失败: player={}", event.getPlayer().getUsername());
            event.setResult(PlayerChatEvent.ChatResult.message("[IP属地：未知]" + filtered));
            return;
        }
        String city = ipCityCache.get(ip);
        if (city != null) {
            logger.info("[ChatFilter] IP属地缓存命中: {} -> {}", ip, city);
            event.setResult(PlayerChatEvent.ChatResult.message("[IP属地：" + city + "]" + filtered));
        } else {
            logger.info("[ChatFilter] 查询IP属地: {}", ip);
            CompletableFuture.runAsync(() -> {
                String cityName = fetchCityByIp(ip);
                if (cityName == null || cityName.isEmpty()) cityName = "未知";
                ipCityCache.put(ip, cityName);
                String msg = "[IP属地：" + cityName + "]" + filtered;
                final String ipFinal = ip;
                final String cityNameFinal = cityName;
                server.getScheduler().buildTask(this, () -> {
                    logger.info("[ChatFilter] IP属地异步回写: {} -> {}", ipFinal, cityNameFinal);
                    event.setResult(PlayerChatEvent.ChatResult.message(msg));
                }).schedule();
            });
            event.setResult(PlayerChatEvent.ChatResult.message("[IP属地：查询中]" + filtered));
        }
    }

    private String filterMessage(String message) {
        String result = message;
        for (String word : prohibitedWords) {
            if (word.isEmpty()) continue;
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "***");
        }
        return result;
    }

    private String getPlayerIp(Player player) {
        try {
            InetSocketAddress addr = (InetSocketAddress) player.getRemoteAddress();
            logger.info("[ChatFilter] 获取IP: player={}, ip={}", player.getUsername(), addr.getAddress().getHostAddress());
            return addr.getAddress().getHostAddress();
        } catch (Exception e) {
            logger.error("[ChatFilter] 获取IP异常: {}", e.getMessage());
            return null;
        }
    }

    private String fetchCityByIp(String ip) {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(new java.net.URI("http://ip-api.com/json/" + ip + "?lang=zh-CN"))
                    .GET()
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            var json = new org.json.JSONObject(response.body());
            return json.optString("city", "");
        } catch (Exception e) {
            return null;
        }
    }
} 