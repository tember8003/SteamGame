package SteamGame.recommend.config.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {
    private final AntPathMatcher matcher = new AntPathMatcher();

    //gemini api는 30분에 3번 (수정될 수 있음) , 나머지는 2초에 한번씩
    private final Map<String, Bucket> buckets = new LinkedHashMap<>() {{
        // 1) 정확 매칭: /api/recommend/input
        put("/api/recommend/input", Bucket.builder()
                .addLimit(Bandwidth.classic(3, Refill.greedy(3, Duration.ofMinutes(30))))
                .build()
        );
        // 2) 와일드카드: /api/recommend/** (input 제외한 나머지)
        put("/api/recommend/**", Bucket.builder()
                .addLimit(Bandwidth.classic(1, Refill.intervally(1, Duration.ofSeconds(2))))
                .build()
        );
    }};

    //그 외 1초에 2번씩
    private final Bucket defaultBucket = Bucket.builder()
            .addLimit(Bandwidth.classic(60, Refill.greedy(2, Duration.ofSeconds(1))))
            .build();

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException
    {
        String path = req.getRequestURI();

        // 요청 경로에 매칭되는 첫 번째 패턴을 찾아서 그 Bucket을 사용
        Bucket bucket = buckets.entrySet().stream()
                .filter(e -> matcher.match(e.getKey(), path))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultBucket);

        if (bucket.tryConsume(1)) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(429);
            res.setCharacterEncoding("UTF-8");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            res.getWriter().write("{\"status\":429, \"error\":\"요청 속도가 너무 빠릅니다!\"}");

        }
    }
}
