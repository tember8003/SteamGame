package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
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
        return webClientBuilder.baseUrl("https://api.steampowered.com/ISteamApps/GetAppList/v2/")
                .exchangeStrategies(strategies)
                .build()
                .get().retrieve()
                .bodyToMono(SteamDTO.SteamAppListResponse.class)
                .flatMap(response -> {
                    List<SteamDTO.SteamApp> validApps = response.getApplist().getApps().stream()
                            .filter(app->app.getName() != null && !app.getName().isBlank())
                            .collect(Collectors.toList());

                    if(validApps.isEmpty()){
                        return Mono.empty();
                    }
                    return Mono.just(validApps.get(random.nextInt(validApps.size())));
                 });
    }
}
