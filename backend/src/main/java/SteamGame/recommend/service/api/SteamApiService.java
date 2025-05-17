package SteamGame.recommend.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SteamApiService {
    private final WebClient.Builder webClientBuilder;
    private final String steam_api_key;
    private final ObjectMapper objectMapper;
    private final String STEAM_STORE_URL = "https://store.steampowered.com/app/";

    public SteamApiService(WebClient.Builder webClientBuilder,
                           @Value("${steam.api.key}") String steam_api_key,
                           ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.steam_api_key = steam_api_key;
        this.objectMapper = objectMapper;
    }

    public List<Long> getOwnedGameIds(String steamId){
        String response =  webClientBuilder.build()
                .get()
                .uri(uri -> uri
                        .scheme("https")
                        .host("api.steampowered.com")
                        .path("/IPlayerService/GetOwnedGames/v1/")
                        .queryParam("key", steam_api_key)
                        .queryParam("steamid", steamId)
                        .queryParam("include_appinfo", "false")
                        .queryParam("include_played_free_games", "true")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
        try {
            JsonNode games = objectMapper
                    .readTree(response)
                    .path("response")
                    .path("games");

            List<Long> appids = new ArrayList<>();
            for (JsonNode g : games) {
                // playtime_forever 필터링도 가능
                log.info(g.path("appid").toString());
                appids.add(g.path("appid").asLong());
            }

            return appids;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Steam API 호출 실패", e);
        }
    }

    public List<Long> getRecentPlayedGameIds(String steamId){
        String json = webClientBuilder.build()
                .get()
                .uri(uri -> uri
                        .scheme("https")
                        .host("api.steampowered.com")
                        .path("/IPlayerService/GetRecentlyPlayedGames/v1/")
                        .queryParam("key", steam_api_key)
                        .queryParam("steamid", steamId)
                        .queryParam("format", "json")
                        .build()
                )
                .retrieve()
                .bodyToMono(String.class)
                .block();

        List<Long> recentAppIds = new ArrayList<>();
        try {
            JsonNode games = objectMapper.readTree(json)
                    .path("response")
                    .path("games");
            for (JsonNode g : games) {
                recentAppIds.add(g.path("appid").asLong());
            }
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Steam API 호출 실패", e);
        }

        return recentAppIds;
    }
}
