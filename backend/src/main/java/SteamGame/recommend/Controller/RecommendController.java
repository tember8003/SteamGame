package SteamGame.recommend.Controller;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Service.RecommendService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    private final RecommendService recommendService;

    public RecommendController(RecommendService service) {
        this.recommendService = service;
    }

    @GetMapping("/random")
    public SteamDTO.SteamApp randomGame(
            @RequestParam String[] tags,
            @RequestParam int review,
            @RequestParam boolean korean_check) {
        return recommendService.findGame(tags, review, korean_check);
    }

    @PostMapping("/input")
    public SteamDTO.RecommendationResult inputRandomGame(
            @RequestBody Map<String, String> body) {
        return recommendService.selectInfo(body.get("input"));
    }

    @PostMapping("/profile")
    public SteamDTO.RecommendationResult randomGameByProfile(@RequestBody Map<String,String> body) {
        return recommendService.recommendByProfile(body.get("steamId"));
    }
}