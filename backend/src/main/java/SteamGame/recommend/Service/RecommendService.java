package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Entity.Game;
import SteamGame.recommend.Repository.GameRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class RecommendService {

    private final Random random = new Random();
    private final GameRepository gameRepository;
    private final RedisTemplate<String,Boolean> redisTemplate;

    //TODO : 스팀 API APP DETAIL에서 정보 가져오기
    @Value("${steam.api.key}")
    private String steam_api_key;
    private final String STEAM_STORE_URL = "https://store.steampowered.com/app/";

    public RecommendService(GameRepository gameRepository, RedisTemplate<String, Boolean> redisTemplate) {
        this.gameRepository = gameRepository;
        this.redisTemplate = redisTemplate;
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
