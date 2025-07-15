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

@Plugin(id = "chatfilter", name = "ChatFilter", version = "1.0", authors = {"cdpyx"})
public class ChatFilterPlugin {
    private final ProxyServer server;
    private Set<String> prohibitedWords = Set.of();
    private final Map<String, String> ipCityCache = new ConcurrentHashMap<>();

    @Inject
    public ChatFilterPlugin(ProxyServer server) {
        this.server = server;
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
                    com.velocitypowered.api.text.TextComponent.of("敏感词列表已加载，共" + words.size() + "个。")
                );
            } catch (Exception e) {
                server.getConsoleCommandSource().sendMessage(
                    com.velocitypowered.api.text.TextComponent.of("敏感词加载失败: " + e.getMessage())
                );
            }
        });
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String filtered = filterMessage(message);
        String ip = getPlayerIp(player);
        if (ip == null) {
            event.setResult(PlayerChatEvent.ChatResult.message("[IP属地：未知]" + filtered));
            return;
        }
        String city = ipCityCache.get(ip);
        if (city != null) {
            event.setResult(PlayerChatEvent.ChatResult.message("[IP属地：" + city + "]" + filtered));
        } else {
            // 异步查询IP属地
            CompletableFuture.runAsync(() -> {
                String cityName = fetchCityByIp(ip);
                if (cityName == null || cityName.isEmpty()) cityName = "未知";
                ipCityCache.put(ip, cityName);
                String msg = "[IP属地：" + cityName + "]" + filtered;
                // 主线程设置聊天内容
                server.getScheduler().buildTask(this, () -> {
                    event.setResult(PlayerChatEvent.ChatResult.message(msg));
                }).schedule();
            });
            // 先返回未知
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
            return addr.getAddress().getHostAddress();
        } catch (Exception e) {
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