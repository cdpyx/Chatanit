package com.cdpyx.chatanit;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import org.json.JSONObject;
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
import com.velocitypowered.api.event.connection.LoginEvent;

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
                // 取 text 字段
                var arr = json.getJSONArray("text");
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
        String message = event.getMessage();
        Player player = event.getPlayer();
        String ip = getPlayerIp(player);
        // 1. IP属地同步获取
        String city = "未知";
        if (ip != null && ("127.0.0.1".equals(ip) || "::1".equals(ip))) {
            city = "本地";
        } else if (ip != null) {
            city = fetchCityByIp(ip); // 必须是同步方法
            if (city == null || city.isEmpty()) city = "未知";
        }
        String ipLabel = "[IP属地：" + city + "]";
        // 2. 敏感词同步检测
        String filteredMsg = message;
        try {
            String url = "https://uapis.cn/api/prohibited?text=" + java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(new java.net.URI(url))
                    .GET()
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            String jsonStr = response.body();
            logger.info("[ChatFilter] 敏感词API原始返回: {}", jsonStr);
            var json = new org.json.JSONObject(jsonStr);
            if (json.has("text")) {
                Object textObj = json.get("text");
                if (textObj instanceof String) {
                    filteredMsg = (String) textObj;
                } else if (textObj instanceof org.json.JSONArray arr && arr.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        sb.append(arr.getString(i));
                    }
                    filteredMsg = sb.toString();
                }
            }
        } catch (Exception e) {
            logger.error("[ChatFilter] 敏感词API调用异常: {}", e.getMessage());
        }
        // 3. 立即 setResult
        event.setResult(PlayerChatEvent.ChatResult.message(ipLabel + filteredMsg));
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = server.getPlayer(event.getPlayer().getUniqueId()).orElse(null);
        if (player == null) return;
        String ip = getPlayerIp(player);
        if (ip != null && !ipCityCache.containsKey(ip)) {
            CompletableFuture.runAsync(() -> {
                String cityName = fetchCityByIp(ip);
                if (cityName == null || cityName.isEmpty()) cityName = "未知";
                ipCityCache.put(ip, cityName);
                logger.info("[ChatFilter] 登录时预查IP属地: {} -> {}", ip, cityName);
            });
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
            var url = "http://ip-api.com/json/" + ip+"?lang=zh-CN";
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(new java.net.URI(url))
                    .GET()
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            var jsonStr = response.body();
            logger.info("[ChatFilter] IP API原始返回: {}", jsonStr);
            var json = new org.json.JSONObject(jsonStr);
            if (json.has("data")) {
                
                String city = json.optString("city", "");
                if (city != null && !city.isEmpty()) {
                    return city;
                } else {
                    return json.optString("isp", "");
                }
            }
            return "";
        } catch (Exception e) {
            return null;
        }
    }
} 