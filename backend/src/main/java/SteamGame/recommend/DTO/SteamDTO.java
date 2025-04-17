package SteamGame.recommend.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class SteamDTO {
    /*
    {
  "applist": {
    "apps": [
      {
        "appid": 10,
        "name": "Counter-Strike"
      },
      {
        "appid": 20,
        "name": "Team Fortress Classic"
      }
    ]
  }
} 형태로 반환되기에 해당 DTO로 설정
     */

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SteamAppListResponse {
        private AppList applist;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppList {
        private List<SteamApp> apps;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SteamApp {
        private Long appid;
        private String name;
        private String shortDescription;
        private String headerImage;
        private String steamStore;
    }
}