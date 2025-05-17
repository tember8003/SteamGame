package SteamGame.recommend.repository;

import SteamGame.recommend.entity.TagCooccurrence;
import SteamGame.recommend.entity.TagPairKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CooccurrenceRepository extends JpaRepository<TagCooccurrence, TagPairKey> {
}
