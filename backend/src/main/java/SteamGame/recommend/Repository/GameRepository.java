package SteamGame.recommend.Repository;

import SteamGame.recommend.DTO.SteamDTO;
import SteamGame.recommend.Entity.Game;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {
    @Query(value = """
        SELECT g.* FROM games g
        JOIN game_tags gt ON g.id = gt.game_id
        JOIN tags t ON t.id = gt.tag_id
        WHERE t.name IN :tagNames
            AND g.review_count >= :review
            AND (:korean_check = false OR g.korean_support = true)
        GROUP BY g.id
        HAVING COUNT(DISTINCT t.name) = :tagCount
        ORDER BY RAND()
        LIMIT 1
    """, nativeQuery = true)
    Optional<Game> findRandomGameByTags(@Param("tagNames") List<String> tagNames, @Param("tagCount") long tagCount, @Param("review") int review, @Param("korean_check") boolean korean_check);

}
