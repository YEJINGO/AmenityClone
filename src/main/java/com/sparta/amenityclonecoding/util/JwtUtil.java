package com.sparta.amenityclonecoding.util;

import com.sparta.amenityclonecoding.dto.TokenDto;
import com.sparta.amenityclonecoding.entity.RefreshToken;
import com.sparta.amenityclonecoding.entity.UserRole;
import com.sparta.amenityclonecoding.repository.RefreshTokenRepository;
import com.sparta.amenityclonecoding.security.UserDetailsServiceImpl;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    //토큰 생성, 검증 담당
    @Value("${jwt.secret.key}")
    private String secretKey; // 암호화/복호화에 필요
    // Access or Refresh 토큰 확인 키
    public static final String ACCESS_KEY = "ACCESS_KEY";
    public static final String REFRESH_KEY = "REFRESH_KEY";
    // Header 의 Key 값
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String AUTHORIZATION_KEY = "auth";
    // Token 식별자
    public static final String BEARER_PREFIX = "Bearer ";
    // 토큰 만료시간
    private static final long ACCESS_TIME = 60 * 60 * 1000L;
    private static final long REFRESH_TIME = 24 * 60 * 60 * 1000L;

    private final UserDetailsServiceImpl userDetailsService;
    private final RefreshTokenRepository refreshTokenRepository;

    private Key key;
    private final SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;

    //PostConstruct  - 왜 어노테이션쓰면서까지 초기화하는가? 상수로 그냥 안하는 이유?
    //초기화 비용관련 시점
    @PostConstruct // 의존성 주입 후 바로 초기화
    public void init() {
        byte[] bytes = Base64.getDecoder().decode(secretKey);
        key = Keys.hmacShaKeyFor(bytes);
    }

    // 액세스 토큰 및 리프레시 토큰 생성
    public TokenDto createAllToken(String userId, UserRole role) {
        return new TokenDto(createToken(userId, role, "Access"), createToken(userId, role, "Refresh"));
    }

    // Request Header 에서 토큰 가져오기
    public String resolveToken(HttpServletRequest request, String token) {
        String tokenName = token.equals("ACCESS_KEY") ? ACCESS_KEY : REFRESH_KEY;
        String bearerToken = request.getHeader(tokenName);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(7);
        }
        return null;
    }

    // JWT 생성하기
    public String createToken(String userId, UserRole role, String type) {
        Date date = new Date();
        long time = type.equals("Access") ? ACCESS_TIME : REFRESH_TIME;

        return BEARER_PREFIX
                + Jwts.builder()
                .setSubject(userId)
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .claim(AUTHORIZATION_KEY, role)
                .setIssuedAt(date)
                .setExpiration(new Date(date.getTime() + time))
                .compact();
    }

    // JWT 검증하기(이상 없으면 true)
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.info("Invalid JWT signature, 유효하지 않은 JWT 서명 입니다.");
        } catch (ExpiredJwtException e) {
            log.info("Expired JWT token, 만료된 JWT token 입니다.");
        } catch (UnsupportedJwtException e) {
            log.info("Unsupported JWT token, 지원되지 않는 JWT 토큰 입니다.");
        } catch (IllegalArgumentException e) {
            log.info("JWT claims is empty, 잘못된 JWT 토큰 입니다.");
        }
        return false;
    }

    // JWT 에서 사용자 정보 가져오기(먼저 유효성 검사 후에 사용해야 합니다)
    // 반환하는 객체.getSubject() 메서드로 username 을 확인할 수 있습니다.
    public String getUserInfoFromToken(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody().getSubject();
    }

    public Authentication createAuthentication(String userId) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(userId); ///////////////////////////////////////////////
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    //리프레시 토큰 검증
    public Boolean refreshTokenValidation(String token) {
        // 1차 토큰 검증
        if (!validateToken(token)) return false;

        // DB에 저장한 토큰 비교
        Optional<RefreshToken> refreshToken = refreshTokenRepository.findRefreshTokenByUserEmail(getUserInfoFromToken(token));

        return refreshToken.isPresent() && token.equals(refreshToken.get().getRefreshToken());
    }

    //액세스 토큰 헤더 설정
    public void setHeaderAccessToken(HttpServletResponse response, String accessToken) {
        response.setHeader(ACCESS_KEY, accessToken);
    }
}
