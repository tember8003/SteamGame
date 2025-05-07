package SteamGame.recommend.Config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    @Value("${security.api-key}")
    private String validApiKey;

    @Value("${security.api-key-header-name}")
    private String headerName;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        String incomeKey = req.getHeader(headerName);
        if (!validApiKey.equals(incomeKey)) {
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.getWriter().write("Invalid API Key");
            return;
        }
        chain.doFilter(req, res);
    }
}
