package SteamGame.recommend.Service;

import SteamGame.recommend.Entity.Tag;
import SteamGame.recommend.Repository.TagRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class InfoService {
    private final TagRepository tagRepository;

    public InfoService(TagRepository tagRepository){
        this.tagRepository = tagRepository;
    }

    private static final Set<String> ALLOWED_ENGLISH =
            Set.of("2D","3D","RPG","FPS","MMO");

    // 한글이 하나라도 들어있는지 체크하는 정규식
    private static final Pattern KOREAN_PATTERN = Pattern.compile(".*[\\uAC00-\\uD7A3].*");

    public List<String> listFilteredTagNames() {
        return tagRepository.findAll().stream()
                .map(Tag::getName)
                .filter(name ->
                        KOREAN_PATTERN.matcher(name).matches()
                                || ALLOWED_ENGLISH.contains(name)
                )
                .distinct()
                .sorted()
                .toList();
    }
}
