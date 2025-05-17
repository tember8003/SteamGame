package SteamGame.recommend.service.tag;

import SteamGame.recommend.entity.Tag;
import SteamGame.recommend.repository.TagRepository;
import SteamGame.recommend.service.api.SteamApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TagService {
    private final ObjectMapper objectMapper;
    private final SteamApiService steamApiService;
    private final TagRepository tagRepository;

    private static final Set<String> ALLOWED_ENGLISH =
            Set.of("2D","3D","RPG","FPS","MMO");

    // 한글이 하나라도 들어있는지 체크하는 정규식
    private static final Pattern KOREAN_PATTERN = Pattern.compile(".*[\\uAC00-\\uD7A3].*");

    public TagService(ObjectMapper objectMapper,
                      SteamApiService steamApiService,
                      TagRepository tagRepository){
        this.objectMapper = objectMapper;
        this.steamApiService = steamApiService;
        this.tagRepository = tagRepository;
    }
    public String[] extractTags(String geminiResponse) {
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

    public List<String> getTopTagsByProfile(String steamId, int topN){
        List<Long> appids = steamApiService.getOwnedGameIds(steamId);

        if(appids.isEmpty()){
            return List.of();
        }

        List<String> allTags = tagRepository.findTagNamesByAppIds(appids);

        return getTopTags(allTags,topN);
    }

    //게임 랜덤 추천 셔플용
    public List<String> shuffleTag(List<String> topTags, int min, int max){
        List<String> shuffled = new ArrayList<>(topTags);

        Collections.shuffle(shuffled);

        int k = min + new Random().nextInt(max - min + 1);
        return shuffled.subList(0, Math.min(k, shuffled.size()));
    }

    public List<String> getTopTags(List<String> tags,int limits){
        Map<String, Long> counts  = tags.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<String> tag_list = counts.entrySet().stream()
                .sorted(Map.Entry.<String,Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limits)
                .map(Map.Entry::getKey)
                .toList();

        return tag_list;
    }

    public List<String> getFilteredTagNames() {
        return tagRepository.findAll().stream()
                .map(Tag::getName)
                .filter(name ->
                        KOREAN_PATTERN.matcher(name).matches()
                                || ALLOWED_ENGLISH.contains(name)
                )
                .distinct()
                .sorted()
                .toList();
    }
}
