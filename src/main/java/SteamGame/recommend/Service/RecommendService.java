package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
public class RecommendService {
    // RecommendService.java 생성자 안에 넣기
    ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(30 * 1024 * 1024)) // 10MB로 설정
            .build();

    @Value("${steam.api.key}")
    private String steam_api_key;
    private final Random random = new Random();
    private final WebClient.Builder webClientBuilder;

    public RecommendService(WebClient.Builder webClientBuilder){
        this.webClientBuilder = webClientBuilder;
    }

    public Mono<SteamDTO.SteamApp> findRandomGame(){
        return Mono.defer(() ->
                webClientBuilder.baseUrl("https://api.steampowered.com/ISteamApps/GetAppList/v2/")
                        .exchangeStrategies(strategies)
                        .build()
                        .get()
                        .retrieve()
                        .bodyToMono(SteamDTO.SteamAppListResponse.class)
                        .flatMap(response -> {
                            List<SteamDTO.SteamApp> validApps = response.getApplist().getApps().stream()
                                    .filter(app -> app.getName() != null && !app.getName().isBlank())
                                    .collect(Collectors.toList());

                            if (validApps.isEmpty()) {
                                return Mono.error(new RuntimeException("앱 목록이 비어 있음"));
                            }

                            SteamDTO.SteamApp randomApp = validApps.get(random.nextInt(validApps.size()));
                            int appId = randomApp.getAppid();

                            return webClientBuilder.baseUrl("https://store.steampowered.com/api/appdetails")
                                    .exchangeStrategies(strategies)
                                    .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                    .build()
                                    .get()
                                    .uri(uriBuilder -> uriBuilder
                                            .queryParam("appids", appId)
                                            .queryParam("l", "koreana")
                                            .build())
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .flatMap(json -> {
                                        if (json.trim().startsWith("<")) {
                                            return Mono.empty();
                                        }

                                        try {
                                            ObjectMapper mapper = new ObjectMapper();
                                            JsonNode rootNode = mapper.readTree(json);
                                            JsonNode appNode = rootNode.path(String.valueOf(appId));
                                            boolean success = appNode.path("success").asBoolean(false);
                                            String type = appNode.path("data").path("type").asText("");

                                            if (success && "game".equalsIgnoreCase(type)) {
                                                return Mono.just(randomApp);
                                            } else {
                                                return Mono.empty();
                                            }
                                        } catch (Exception e) {
                                            return Mono.empty(); // 파싱 실패 시에도 재시도
                                        }
                                    });
                        })
        ).flatMap(result -> {
            if (result != null) return Mono.just(result);
            return findRandomGame(); // 실패했으면 재귀 호출
        });
    }
}
