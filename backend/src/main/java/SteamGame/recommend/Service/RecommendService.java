package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecommendService {
    //WebClient 크기 조절
    ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(30 * 1024 * 1024)) // 10MB로 설정
            .build();

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    private final String STEAM_APP_LIST_KEY = "steam_app_list";

    @Value("${steam.api.key}")
    private String steam_api_key;

    private final Random random = new Random();

    @Autowired
    private WebClient.Builder webClientBuilder;

    /*
    public RecommendService(WebClient.Builder webClientBuilder){
        this.webClientBuilder = webClientBuilder;
    }
    */

    public Mono<SteamDTO.SteamApp> findRandomGame(String[] tags, int review) {
        return getValidAppList().flatMap(appList -> tryFindValidGame(appList, 1000, tags, review)); // 최대 1000번 시도
    }

    private Mono<List<SteamDTO.SteamApp>> getValidAppList() {
        List<SteamDTO.SteamApp> cachedAppList = (List<SteamDTO.SteamApp>) redisTemplate.opsForValue().get(STEAM_APP_LIST_KEY);
        if (cachedAppList != null && !cachedAppList.isEmpty()) {
            return Mono.just(cachedAppList);
        }

        return webClientBuilder.baseUrl("https://api.steampowered.com/ISteamApps/GetAppList/v2/")
                .exchangeStrategies(strategies)
                .build()
                .get()
                .retrieve()
                .bodyToMono(SteamDTO.SteamAppListResponse.class)
                .map(response -> {
                    List<SteamDTO.SteamApp> list = response.getApplist().getApps().stream()
                            .filter(app -> app.getName() != null && !app.getName().isBlank())
                            .collect(Collectors.toList());
                    redisTemplate.opsForValue().set(STEAM_APP_LIST_KEY, list);
                    return list;
                });
    }

    private Mono<SteamDTO.SteamApp> tryFindValidGame(List<SteamDTO.SteamApp> appList, int attemptsLeft, String[] tags, int review) {
        if (attemptsLeft <= 0) return Mono.error(new RuntimeException("유효한 게임을 찾을 수 없음"));

        SteamDTO.SteamApp randomApp = appList.get(random.nextInt(appList.size()));
        int appId = randomApp.getAppid();

        return webClientBuilder.baseUrl("https://store.steampowered.com/api/appdetails")
                .exchangeStrategies(strategies)
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam("appids", appId)
                        .queryParam("l","korean")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(json -> {
                    if (json.trim().startsWith("<")) return Mono.empty();

                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode rootNode = mapper.readTree(json);
                        JsonNode appNode = rootNode.path(String.valueOf(appId));
                        boolean success = appNode.path("success").asBoolean(false);
                        String type = appNode.path("data").path("type").asText("");
                        int reviewCount = appNode.path("data").path("recommendations").path("total").asInt(0);
                        JsonNode genresNode = appNode.path("data").path("genres");
                        JsonNode categoriesNode = appNode.path("data").path("categories");

                        boolean check_tag = true;
                        // genres + categories 합치기
                        List<String> allTags = new ArrayList<>();
                        genresNode.forEach(node -> allTags.add(node.path("description").asText("").toLowerCase()));
                        categoriesNode.forEach(node -> allTags.add(node.path("description").asText("").toLowerCase()));
                        

                        for(int i=0; i<allTags.size(); i++){
                            log.info("장르 + 카테고리 디버깅: "+allTags.get(i));
                        }

                        for(String tag : tags){
                            if(!allTags.contains(tag.toLowerCase())){
                                check_tag=false;
                                break;
                            }
                        }

                        if (success && "game".equalsIgnoreCase(type) && review <= reviewCount && check_tag) {
                            JsonNode dataNode = appNode.path("data");
                            int steamAppId = dataNode.path("steam_appid").asInt();
                            String name = dataNode.path("name").asText();
                            String shortDescription = dataNode.path("short_description").asText("");
                            String headerImage = dataNode.path("header_image").asText("");

                            SteamDTO.SteamApp steamApp = new SteamDTO.SteamApp();
                            steamApp.setAppid(steamAppId);
                            steamApp.setName(name);
                            steamApp.setShortDescription(shortDescription);
                            steamApp.setHeaderImage(headerImage);

                            return Mono.just(steamApp);
                        }
                    } catch (Exception ignored) {}

                    //steam api 오류 피하기
                    return Mono.delay(Duration.ofMillis(300))
                            .then(tryFindValidGame(appList, attemptsLeft - 1, tags, review)); // 재귀로 다시 시도
                });
    }



    @Scheduled(cron = "0 0 5 * * *") // 매일 새벽 3시
    public void updateSteamAppList() {
        log.info("Steam App List 업데이트 중...");

        webClientBuilder.baseUrl("https://api.steampowered.com/ISteamApps/GetAppList/v2/")
                .exchangeStrategies(strategies)
                .build()
                .get()
                .retrieve()
                .bodyToMono(SteamDTO.SteamAppListResponse.class)
                .map(response -> {
                    List<SteamDTO.SteamApp> appList = response.getApplist().getApps().stream()
                            .filter(app -> app.getName() != null && !app.getName().isBlank())
                            .collect(Collectors.toList());

                    redisTemplate.opsForValue().set(STEAM_APP_LIST_KEY, appList);
                    log.info("steam App List Redis에 저장 완료 (" + appList.size() + "개)");
                    return appList;
                })
                .subscribe();
    }
}
