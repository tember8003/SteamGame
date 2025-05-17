package SteamGame.recommend.controller;

import SteamGame.recommend.dto.SteamDTO;
import SteamGame.recommend.service.RecommendService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RecommendController {

    private final RecommendService recommendService;

    public RecommendController(RecommendService service) {
        this.recommendService = service;
    }

    @GetMapping("/recommend/random")
    public SteamDTO.SteamApp randomGame(
            @RequestParam String[] tags,
            @RequestParam int review,
            @RequestParam(defaultValue = "false") boolean korean_check,
            @RequestParam(required = false) Boolean free_check) {
        return recommendService.findGame(tags, review, korean_check, free_check);
    }

    @PostMapping("/recommend/input")
    public SteamDTO.RecommendationResult inputRandomGame(
            @RequestBody Map<String, String> body) {
        return recommendService.selectInfo(body.get("input"));
    }

    @PostMapping("/recommend/profile")
    public SteamDTO.RecommendationResult randomGameByProfile(@RequestBody Map<String,String> body) {
        return recommendService.recommendByProfile(body.get("steamId"));
    }

    @PostMapping("/recommend/RecentPlay")
    public SteamDTO.RecommendationResult randomGameByRecentPlay(@RequestBody Map<String,String> body){
        return recommendService.recommendByRecentPlay(body.get("steamId"));
    }

    @GetMapping("/tags")
    public List<String> getTags(){
        return recommendService.getTags();
    }
}