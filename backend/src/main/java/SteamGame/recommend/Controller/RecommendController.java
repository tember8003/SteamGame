package SteamGame.recommend.Controller;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Service.RecommendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RequestMapping("/api")
@RestController
public class RecommendController {
    private final RecommendService recommendService;

    RecommendController(RecommendService recommendService){
        this.recommendService = recommendService;
    }

    //태그 : 인디 / 액션 / 생존 / 어드벤처 / 캐주얼 / 싱글 플레이어 / RPG / 시뮬레이션 / 전략 / 비주얼 노벨 등등
    @GetMapping("/recommend/random")
    public Mono<SteamDTO.SteamApp> RandomGame(@RequestParam String[] tags, @RequestParam int review, @RequestParam boolean korean_check){
        return recommendService.findGame(tags, review,korean_check);
    }
}
