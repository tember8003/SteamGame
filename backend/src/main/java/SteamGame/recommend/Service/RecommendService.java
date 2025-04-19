package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Entity.Game;
import SteamGame.recommend.Repository.GameRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        for (int i = 0; i < 5; i++) {
            Optional<Game> optionalGame = gameRepository.findRandomGameByTags(tagList, tagList.size(), review, korean_check);

            if (optionalGame.isEmpty()) {
                throw new RuntimeException("조건에 맞는 게임을 찾을 수 없습니다.");
            }

            Game candidate = optionalGame.get();
            String redisKey = "recommended:" + candidate.getAppid();

            if (!Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                redisTemplate.opsForValue().set(redisKey, true, Duration.ofMinutes(30));
                return Mono.just(convertToDTO(candidate));
            }
        }

        throw new RuntimeException("조건에 맞는 새로운 게임을 찾을 수 없습니다. (중복으로 인해 추천 실패)");
    }


    public Mono<SteamDTO.SteamApp> selectInfo(String input) {
        String prompt = """
        다음 문장을 보고 아래 태그 중 관련된 태그를 최대 4개 추출해 JSON 배열 형태로 출력해.
        
        태그 목록:
        ["2D", "3D", "RPG", "액션", "어드벤처", "캐주얼", "인디", "전략", "시뮬레이션", "멀티 플레이어", "싱글 플레이어", "협동", "앞서 해보기", "오픈 월드", "풍부한 스토리", "퍼즐", "플랫폼", "슈팅", "FPS", "3인칭", "VR", "판타지", "SF", "공포", "생존", "애니메이션", "비주얼 노벨", "픽셀 그래픽", "턴제", "카드 게임", "샌드박스", "건설", "크래프팅", "미스터리", "코미디", "어두운", "고어", "폭력", "귀여운", "심리적 공포", "수사", "좀비", "온라인 협동", "핵 앤 슬래시", "격투", "비뎀업", "탄막 슈팅", "횡스크롤", "로그라이크", "로그라이트", "액션 RPG", "액션 어드벤처", "MMO", "JRPG", "던전 크롤러", "매치 3", "스포츠", "레이싱", "1인칭", "3인칭 슈팅", "리듬", "음악", "경영", "클리커", "전술", "잠입", "탐험"]
        
                예시 1:
                입력 문장: 롤 같은 게임
                출력: ["MOBA", "멀티 플레이어", "실시간"]
                
                예시 2:
                입력 문장: 친구와 같이 즐길 수 있는 로그라이크 게임 추천해줘
                출력: ["로그라이크", "멀티 플레이어", "협동"]
                
                예시 3:
                입력 문장: 혼자 조용히 몰입해서 할 수 있는 감성적인 스토리 게임
                출력: ["싱글플레이어", "풍부한 스토리", "어드벤처"]
                예시 4:
                입력 문장: 스팀덱으로 하기 좋은 픽셀 그래픽 로그라이크
                출력: ["픽셀 그래픽", "로그라이크", "싱글 플레이어"]
                
                예시 5:
                입력 문장: 친구랑 밤새도록 할 수 있는 도전적인 게임
                출력: ["협동", "로그라이크", "고난이도"]
               
                이런 식으로 스팀 태그 목록에서 뽑아내서 출력해줘.
                
                입력 문장: %s
                출력:
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
                .uri(GEMINI_URL + "?key=" + gemini_api_key)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    log.info(">>> Gemini 응답: {}", response);
                    String[] tags = extractTags(response);
                    log.info(">>> 추출된 태그: {}", Arrays.toString(tags));
                    return findGame(tags, 500, true);
                })
                .doOnError(e -> log.error(">>> Gemini 호출 중 예외 발생", e));
    }

    public String[] extractTags(String geminiResponse) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(geminiResponse);

            JsonNode textNode = root.path("candidates").get(0)
                    .path("content").path("parts").get(0).path("text");

            if (!textNode.isMissingNode()) {
                String text = textNode.asText();

                Pattern pattern = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(text);

                if (matcher.find()) {
                    String jsonArray = matcher.group(0);
                    return objectMapper.readValue(jsonArray, String[].class);
                } else {
                    log.warn(">>> 태그 배열 형식을 파싱하지 못했습니다. 응답 텍스트: {}", text);
                }
            } else {
                log.warn(">>> Gemini 응답 구조에서 text 필드를 찾지 못했습니다.");
            }
        } catch (Exception e) {
            log.error(">>> Gemini 응답에서 태그 파싱 중 오류 발생", e);
        }

        return new String[0];
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
