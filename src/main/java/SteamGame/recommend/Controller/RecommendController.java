package SteamGame.recommend.Controller;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Service.RecommendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RequestMapping("/api")
@RestController
public class RecommendController {
    private final RecommendService recommendService;

    RecommendController(RecommendService recommendService){
        this.recommendService = recommendService;
    }

    @GetMapping("/recommand/random")
    public Mono<SteamDTO.SteamApp> RandomGame(){
        return recommendService.findRandomGame();
    }
}
