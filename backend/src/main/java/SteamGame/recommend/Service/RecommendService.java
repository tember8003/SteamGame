package SteamGame.recommend.Service;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Entity.Game;
import SteamGame.recommend.Repository.GameRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Service
public class RecommendService {

    private final Random random = new Random();
    private final GameRepository gameRepository;

    //TODO : 스팀 API APP DETAIL에서 정보 가져오기
    @Value("${steam.api.key}")
    private String steam_api_key;
    private final String STEAM_STORE_URL = "https://store.steampowered.com/app/";

    public RecommendService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }


    public Mono<SteamDTO.SteamApp> findGame(String[] tags, int review, boolean korean_check) {
        List<String> tagList = Arrays.asList(tags);

        Game game = gameRepository.findRandomGameByTags(tagList, tagList.size(), review, korean_check)
                .orElseThrow(() -> new RuntimeException("조건에 맞는 게임을 찾을 수 없습니다."));

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
