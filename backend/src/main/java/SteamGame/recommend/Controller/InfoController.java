package SteamGame.recommend.Controller;

import SteamGame.recommend.Service.InfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class InfoController {

    private final InfoService infoService;

    public InfoController(InfoService infoService){
        this.infoService = infoService;
    }
    @GetMapping("/tags")
    public List<String> getTags(){
        return infoService.listFilteredTagNames();
    }
}
