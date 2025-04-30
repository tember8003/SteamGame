package SteamGame.recommend.Entity;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor
public class TagPairKey implements Serializable {
    private String tag1;
    private String tag2;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TagPairKey)) return false;
        TagPairKey tagPairKey = (TagPairKey) o;
        return Objects.equals(tag1, tagPairKey.tag1) &&
                Objects.equals(tag2, tagPairKey.tag2);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag1, tag2);
    }
}
