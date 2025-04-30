package SteamGame.recommend.Repository;

import SteamGame.recommend.Entity.TagCooccurrence;
import SteamGame.recommend.Entity.TagPairKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CooccurrenceRepository extends JpaRepository<TagCooccurrence, TagPairKey> {
}
