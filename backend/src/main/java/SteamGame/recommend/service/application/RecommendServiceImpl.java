package SteamGame.recommend.service.application;

import SteamGame.recommend.dto.SteamDTO;
import SteamGame.recommend.entity.TagPairKey;
import SteamGame.recommend.repository.TagRepository;
import SteamGame.recommend.service.api.GeminiApiService;
import SteamGame.recommend.service.api.SteamApiService;
import SteamGame.recommend.service.tag.TagService;
import SteamGame.recommend.utils.EncryptUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Slf4j
@Service
public class RecommendServiceImpl implements RecommendService {

    private static final int CO_THRESHOLD = 5;
    private static final int DEFAULT_REVIEW = 1000;
    private static final List<String> FALLBACK_TAGS = List.of("싱글 플레이어","멀티플레이어");

    private final SteamApiService steamApiService;
    private final GeminiApiService geminiApiService;
    private final TagService tagService;
    private final TagRepository tagRepository;
    private final CacheService cacheService;
    private final GameFinderService gameFinderService;
    private final CooccurrenceService cooccurrenceService;

    public RecommendServiceImpl(
            SteamApiService steamApiService,
            GeminiApiService geminiApiService,
            TagService tagService,
            TagRepository tagRepository,
            CacheService cacheService,
            GameFinderService gameFinderService,
            CooccurrenceService cooccurrenceService
    ) {
        this.steamApiService = steamApiService;
        this.geminiApiService = geminiApiService;
        this.tagService = tagService;
        this.tagRepository = tagRepository;
        this.cacheService = cacheService;
        this.gameFinderService = gameFinderService;
        this.cooccurrenceService = cooccurrenceService;
    }

    //태그, 리뷰, 한글화, 무료여부 등 조건으로 게임 찾기.
    @Override
    @Transactional(readOnly=true)
    public SteamDTO.SteamApp findGame(String[] tags, int review, boolean koreanCheck, Boolean freeCheck) {
        return gameFinderService.findNonDuplicate(tags,review,koreanCheck,freeCheck);
    }

    //Gemini API를 활용해 게임 태그 추출해 게임 찾기
    @Override
    public SteamDTO.RecommendationResult selectInfo(String input) {
        if (input == null || input.length() < 3) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "입력 문장이 너무 짧습니다.");
        }

        //캐시 검사
        String shaInput = EncryptUtils.sha256(input);
        List<String> cachingTags = cacheService.getCachedTags(shaInput);
        if (cachingTags != null && !cachingTags.isEmpty()) {
            SteamDTO.SteamApp game = findGame(cachingTags.toArray(new String[0]), DEFAULT_REVIEW, true,null);
            return toResult(cachingTags, game);
        }

        boolean hit = cachingTags != null && !cachingTags.isEmpty();
        log.info("… 캐시 히트: {}", hit);

        String response =geminiApiService.getGeminiAnswer(input);

        log.info("Gemini 응답: {}", response);
        String[] tags = tagService.extractTags(response);
        if (tags.length == 0) {
            log.warn("태그 추출 실패, 기본 태그로 대체");
            tags = FALLBACK_TAGS.toArray(new String[0]);
        }

        // 캐시에 저장
        cacheService.cacheTags(shaInput,Arrays.asList(tags));

        // 최종 추천
        SteamDTO.SteamApp game = findGame(tags, DEFAULT_REVIEW, true,null);
        return toResult(Arrays.asList(tags), game);
    }

    //스팀 사용자 프로필에 있는 게임들 리스트를 받아와 태그 뽑아내기
    @Override
    public SteamDTO.RecommendationResult recommendByProfile(String steamId){
        List<String> topTags = tagService.getTopTagsByProfile(steamId,8);

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
        List<String> tags = tagService.shuffleTag(topTags,3,5);

        SteamDTO.SteamApp game = recommendWithCooccurrence(tags);

        return new SteamDTO.RecommendationResult(tags, game);
    }

    //사용자 프로필에서 뽑아낸 태그들을 바탕으로 게임 찾기
    @Override
    public SteamDTO.SteamApp recommendWithCooccurrence(List<String> topTags) {
        Optional<TagPairKey> optKey = cooccurrenceService.findOptimalPairKey(topTags, CO_THRESHOLD);

        if (optKey.isPresent()) {
            TagPairKey key = optKey.get();
            return findGame(
                    new String[]{ key.getFirstTag(), key.getSecondTag() },
                    DEFAULT_REVIEW,
                    true,
                    null
            );
        }

        for (String tag : topTags) {
            try {
                return findGame(new String[]{tag}, DEFAULT_REVIEW, true, null);
            } catch (ResponseStatusException ignored) { }
        }

        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "추천 가능한 게임이 없습니다."
        );
    }

    //최근 플레이(2주) 게임 태그들을 뽑아내 게임 찾기
    @Override
    @Transactional(readOnly = true)
    public SteamDTO.RecommendationResult recommendByRecentPlay(String steamId) {
        List<Long> recentAppIds = steamApiService.getRecentPlayedGameIds(steamId);

        if (recentAppIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "최근 플레이한 게임이 없습니다.");
        }

        List<String> allTags = tagRepository.findTagNamesByAppIds(recentAppIds);

        List<String> topTags = tagService.getTopTags(allTags,6);

        List<String> tags = tagService.shuffleTag(topTags,3,4);

        SteamDTO.SteamApp game = findGame(
                tags.toArray(new String[0]), DEFAULT_REVIEW, true, null);

        return new SteamDTO.RecommendationResult(tags, game);
    }

    //전체 태그 반환
    @Override
    public List<String> getTags(){
        return tagService.getFilteredTagNames();
    }

    // RecommendationResult(게임 추천에 사용된 태그, 추천된 게임 정보) 규격에 맞추기
    private SteamDTO.RecommendationResult toResult(List<String> tags, SteamDTO.SteamApp game) {
        SteamDTO.RecommendationResult r = new SteamDTO.RecommendationResult();
        r.setUsedTags(tags);
        r.setRecommendedGame(game);
        return r;
    }
}
