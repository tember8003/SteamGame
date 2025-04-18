package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Entity.Game;
import SteamGame.recommend.Repository.GameRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class RecommendService {

    private final GameRepository gameRepository;
    private final RedisTemplate<String,Boolean> redisTemplate;
    private final WebClient.Builder webClientBuilder;

    //TODO : 스팀 API APP DETAIL에서 정보 가져오기
    @Value("${steam.api.key}")
    private String steam_api_key;



    @Value("${spring.ai.google.api-key}")
    private String gemini_api_key;


    private final String STEAM_STORE_URL = "https://store.steampowered.com/app/";
    private final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    public RecommendService(GameRepository gameRepository, RedisTemplate<String, Boolean> redisTemplate, WebClient.Builder webClientBuilder) {
        this.gameRepository = gameRepository;
        this.redisTemplate = redisTemplate;
        this.webClientBuilder = webClientBuilder;
    }


    public Mono<SteamDTO.SteamApp> findGame(String[] tags, int review, boolean korean_check) {
        List<String> tagList = Arrays.asList(tags);
        Game game = null;
        String redisKey = null;

        for(int i=0; i<5; i++){
            game = gameRepository.findRandomGameByTags(tagList, tagList.size(), review, korean_check)
                    .orElseThrow(() -> new RuntimeException("조건에 맞는 게임을 찾을 수 없습니다."));

            redisKey = "recommended:"+String.valueOf(game.getAppid());

            if(!Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))){
                redisTemplate.opsForValue().set(redisKey, true, Duration.ofMinutes(30));
                break;
            }

            game=null;
        }
        if (game == null) {
            throw new RuntimeException("조건에 맞는 새로운 게임을 찾을 수 없습니다. (중복으로 인해 추천 실패)");
        }

        return Mono.just(convertToDTO(game));
    }


    public Mono<SteamDTO.SteamApp> selectInfo(String input) {
        String prompt = """
            다음 문장을 보고 스팀 게임 태그 중 관련된 태그를 최대 5개 추출해 JSON 배열 형태로 출력해주세요.
            문장: %s

            형식: ["tag1", "tag2", ...]
            """.formatted(input);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "temperature", 1,
                        "maxOutputTokens", 8192
                )
        );

        return webClientBuilder.build()
                .post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp-image-generation:generateContent?key=" + gemini_api_key)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(response -> log.info(">>> Gemini 응답: {}", response))
                .doOnError(e -> log.error(">>> Gemini 호출 중 예외 발생", e))
                .then(Mono.empty());  // 반환값 없이 종료
    }


    private SteamDTO.SteamApp convertToDTO(Game game) {
        SteamDTO.SteamApp app = new SteamDTO.SteamApp();
        app.setName(game.getName());
        app.setAppid(game.getAppid());
        app.setShortDescription(game.getDescription());
        app.setHeaderImage(game.getImageUrl());
        app.setSteamStore(STEAM_STORE_URL + game.getAppid() + "?l=korean");
        return app;
    }

}
