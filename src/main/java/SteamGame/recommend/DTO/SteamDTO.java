package SteamGame.recommend.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class SteamDTO {

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
        private int appid;
        private String name;
    }
}