package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Entity.Game;
import SteamGame.recommend.Entity.TagCooccurrence;
import SteamGame.recommend.Entity.TagPairKey;
import SteamGame.recommend.Repository.CooccurrenceRepository;
import SteamGame.recommend.Repository.GameRepository;
import SteamGame.recommend.Repository.TagRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecommendService {

    private final GameRepository gameRepository;
    private final RedisTemplate<String,String> redisTemplate;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final TagRepository tagRepository;
    private final CooccurrenceRepository cooccurrenceRepository;
    private static final int CO_THRESHOLD = 5;

    //TODO : 스팀 API APP DETAIL에서 정보 가져오기
    @Value("${steam.api.key}")
    private String steam_api_key;

    @Value("${spring.ai.google.api-key}")
    private String gemini_api_key;

    private final String STEAM_STORE_URL = "https://store.steampowered.com/app/";
    private final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final List<String> FALLBACK_TAGS = List.of("싱글 플레이어","멀티플레이어");

    public RecommendService(GameRepository gameRepository, RedisTemplate<String, String> redisTemplate,
                            WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                            TagRepository tagRepository, CooccurrenceRepository cooccurrenceRepository) {
        this.gameRepository = gameRepository;
        this.redisTemplate = redisTemplate;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.tagRepository = tagRepository;
        this.cooccurrenceRepository=cooccurrenceRepository;
    }

    @Transactional(readOnly=true)
    public SteamDTO.SteamApp findGame(String[] tags, int review, boolean korean_check, Boolean free_check) {
        List<String> tagList = Arrays.asList(tags);

        for (int i = 0; i < 5; i++) {
            Optional<Game> optionalGame = gameRepository.findRandomGameByTags(tagList, tagList.size(), review, korean_check , free_check);

            if (optionalGame.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "조건에 맞는 게임을 찾을 수 없습니다.");
            }

            Game candidate = optionalGame.get();
            String redisKey = "recommended:" + candidate.getAppid();

            if (!"true".equals(redisTemplate.opsForValue().get(redisKey))) {
                redisTemplate.opsForValue().set(redisKey, "true", Duration.ofMinutes(30));
                return convertToDTO(candidate);
            }
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"조건에 맞는 새로운 게임을 찾을 수 없습니다. (중복으로 인해 추천 실패)");
    }

    public SteamDTO.RecommendationResult selectInfo(String input) {
        String shaInput = sha256(input);
        String redisKey = "gemini:tag:"+shaInput;

        if (input == null || input.length() < 3) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "입력 문장이 너무 짧습니다.");
        }

        //캐시 검사
        List<String> cachingTags = redisTemplate.opsForList().range(redisKey, 0, -1);
        if (cachingTags != null && !cachingTags.isEmpty()) {
            SteamDTO.SteamApp game = findGame(cachingTags.toArray(new String[0]), 500, true,null);
            return toResult(cachingTags, game);
        }

        boolean hit = cachingTags != null && !cachingTags.isEmpty();
        log.info("… 캐시 히트: {}", hit);

        String prompt = buildPrompt(input);

        Map<String,Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("temperature",1, "maxOutputTokens",8192)
        );


        String response = webClientBuilder.build()
                .post()
                .uri(GEMINI_URL + "?key=" + gemini_api_key)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(), "Gemini API 오류"))
                )
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        log.info("Gemini 응답: {}", response);
        String[] tags = extractTags(response);
        if (tags.length == 0) {
            log.warn("태그 추출 실패, 기본 태그로 대체");
            tags = FALLBACK_TAGS.toArray(new String[0]);
        }

        // 캐시에 저장
        redisTemplate.opsForList().rightPushAll(redisKey, Arrays.asList(tags));
        redisTemplate.expire(redisKey, Duration.ofHours(6));

        // 최종 추천
        SteamDTO.SteamApp game = findGame(tags, 500, true,null);
        return toResult(Arrays.asList(tags), game);
    }

    public SteamDTO.RecommendationResult recommendByProfile(String steamId){
        List<String> topTags = getTopTagsByProfile(steamId,8);

        if(topTags.isEmpty()){
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "플레이한 게임이 없거나 태그를 찾을 수 없습니다."
            );
        }

        /* 상위 태그 2개 - 랜덤 태그 2개 로직
        List<String> selectedTags = new ArrayList<>();
        selectedTags.add(topTags.get(0));
        if (topTags.size() > 1) {
            selectedTags.add(topTags.get(1));
        }

        List<String> rest = new ArrayList<>();
        if (topTags.size() > 2) {
            rest.addAll(topTags.subList(2, topTags.size()));
            Collections.shuffle(rest);
            for (int i = 0; i < 2 && i < rest.size(); i++) {
                selectedTags.add(rest.get(i));
            }
        }
         */
        List<String> tags = shuffleTag(topTags,3,5);

        SteamDTO.SteamApp game = recommendWithCooccurrence(tags.toArray(new String[0]));

        return new SteamDTO.RecommendationResult(tags, game);
    }

    public SteamDTO.SteamApp recommendWithCooccurrence(String[] topTags) {
        for (int i = 0; i < topTags.length; i++) {
            for (int j = i + 1; j < topTags.length; j++) {
                String t1 = topTags[i], t2 = topTags[j];

                String tag1 = t1.compareTo(t2) < 0 ? t1 : t2;
                String tag2 = t1.compareTo(t2) < 0 ? t2 : t1;

                Optional<TagCooccurrence> opt =
                        cooccurrenceRepository.findById(new TagPairKey(tag1, tag2));
                if (opt.isPresent() && opt.get().getCount() >= CO_THRESHOLD) {
                    return findGame(
                            new String[]{tag1, tag2}, 500, true,null);
                }
            }
        }

        for (String tag : topTags) {
            try {
                return findGame(new String[]{tag}, 500, true,null);
            } catch (ResponseStatusException ignored) {}
        }

        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "추천 가능한 게임이 없습니다.");
    }

    public List<String>  getTopTagsByProfile(String steamId,int topN){
        List<Long> appids = fetchOwnedAppIdsByProfile(steamId);

        if(appids.isEmpty()){
            return List.of();
        }

        List<String> allTags = tagRepository.findTagNamesByAppIds(appids);
        Map<String, Long> counts = allTags.stream()
                .collect(Collectors.groupingBy(
                        Function.identity(), Collectors.counting()));

        return counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String,Long>comparingByValue(Comparator.reverseOrder()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<Long> fetchOwnedAppIdsByProfile (String steamId) {
        String response = webClientBuilder.build()
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

    @Transactional(readOnly = true)
    public SteamDTO.RecommendationResult recommendByRecentPlay(String steamId) {
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

        if (recentAppIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "최근 플레이한 게임이 없습니다.");
        }

        List<String> allTags = tagRepository.findTagNamesByAppIds(recentAppIds);
        Map<String, Long> counts  = allTags.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<String> topTags = counts.entrySet().stream()
                .sorted(Map.Entry.<String,Long>comparingByValue(Comparator.reverseOrder()))
                .limit(4)
                .map(Map.Entry::getKey)
                .toList();

        SteamDTO.SteamApp game = findGame(
                topTags.toArray(new String[0]), 500, true, null);

        return new SteamDTO.RecommendationResult(topTags, game);
    }

    //게임 랜덤 추천 셔플용
    private List<String> shuffleTag(List<String> topTags, int min, int max){
        List<String> shuffled = new ArrayList<>(topTags);

        Collections.shuffle(shuffled);

        int k = min + new Random().nextInt(max - min + 1);
        return shuffled.subList(0, Math.min(k, shuffled.size()));
    }

    private String buildPrompt(String input) {
        return """
        다음 문장을 보고 아래 태그 중 관련된 태그를 최대 4개 추출해 JSON 배열 형태로 출력해.
        
        태그 목록:
        ["2D", "3D", "RPG", "액션", "어드벤처", "캐주얼", "인디", "전략", "시뮬레이션", "멀티플레이어", "싱글 플레이어", "협동", "앞서 해보기", "오픈 월드", "풍부한 스토리", "퍼즐", "플랫폼", "슈팅", "FPS", "3인칭", "VR", "판타지", "SF", "공포", "생존", "애니메이션", "비주얼 노벨", "픽셀 그래픽", "턴제", "카드 게임", "샌드박스", "건설", "크래프팅", "미스터리", "코미디", "어두운", "고어", "폭력", "귀여운", "심리적 공포", "수사", "좀비", "온라인 협동", "핵 앤 슬래시", "격투", "비뎀업", "탄막 슈팅", "횡스크롤", "로그라이크", "로그라이트", "액션 RPG", "액션 어드벤처", "MMO", "JRPG", "던전 크롤러", "매치 3", "스포츠", "레이싱", "1인칭", "3인칭 슈팅", "리듬", "음악", "경영", "클리커", "전술", "잠입", "탐험"]
        
        예시 1:
        입력 문장: 롤 같은 게임
        출력: ["MOBA", "멀티플레이어", "실시간"]
        
        예시 2:
        입력 문장: 친구와 같이 즐길 수 있는 로그라이크 게임 추천해줘
        출력: ["로그라이크", "멀티플레이어", "협동"]
        
        예시 3:
        입력 문장: 혼자 조용히 몰입해서 할 수 있는 감성적인 스토리 게임
        출력: ["싱글 플레이어", "풍부한 스토리", "어드벤처"]
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
    }

    private SteamDTO.RecommendationResult toResult(List<String> tags, SteamDTO.SteamApp game) {
        SteamDTO.RecommendationResult r = new SteamDTO.RecommendationResult();
        r.setUsedTags(tags);
        r.setRecommendedGame(game);
        return r;
    }

    private String[] extractTags(String geminiResponse) {
        try {
            JsonNode root = objectMapper.readTree(geminiResponse);

            JsonNode textNode = root.path("candidates").get(0)
                    .path("content").path("parts").get(0).path("text");

            if (!textNode.isMissingNode()) {
                String text = textNode.asText();

                Pattern pattern = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(text);

                if (matcher.find()) {
                    String jsonArray = matcher.group(0);
                    String[] tags = objectMapper.readValue(jsonArray, String[].class);

                    return Arrays.copyOf(tags, Math.min(tags.length, 4));
                } else {
                    log.warn("태그 배열 형식을 파싱하지 못했습니다. 응답 텍스트: {}", text);
                }
            } else {
                log.warn("Gemini 응답 구조에서 text 필드를 찾지 못했습니다.");
            }
        } catch (Exception e) {
            log.error("Gemini 응답에서 태그 파싱 중 오류 발생", e);
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

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sha256Bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : sha256Bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 실패", e);
        }
    }
}
