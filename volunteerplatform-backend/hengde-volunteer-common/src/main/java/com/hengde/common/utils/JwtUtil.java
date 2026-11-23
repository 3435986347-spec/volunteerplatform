package com.hengde.common.utils;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（基于 jjwt 0.12.x 实现）。
 *
 * <p>JWT（JSON Web Token）是一段「自带签名的字符串」：服务端用密钥签发，之后客户端每次
 * 请求都带上它，服务端用同一把密钥验签，就能确认「这个 token 是我发的、没被篡改」，
 * 从而识别出是哪个用户——不需要在服务端存会话。</p>
 *
 * <p>这里把用户 id 放进 token 的 claim（载荷）里：签发时塞进去，验证时取出来。</p>
 *
 * <p><b>V1 使用说明：</b>V1 的多角色登录态与鉴权统一走 <b>Sa-Token</b>（token 存 Redis），
 * 由 Sa-Token 负责登录、签发/校验 token、角色权限。<b>本类暂不接入</b>，保留以备 Sa-Token
 * 未覆盖的特殊场景使用；切勿与 Sa-Token 并行成两套 token 体系。</p>
 *
 * @author hengde
 */
public class JwtUtil {

    /**
     * 签名密钥。先写死，后续应改为从配置文件 / 环境变量读取，切勿把生产密钥提交到代码库。
     * <p>注意：HS256 要求密钥至少 256 位（32 字节），这串字符已满足。</p>
     */
    private static final String SECRET = "hengde-volunteer-secret-key-please-change-in-prod";

    /** token 有效期：7 天（毫秒） */
    private static final long EXPIRE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** 存放用户 id 的 claim 键名 */
    private static final String CLAIM_USER_ID = "userId";

    /** 由密钥字符串生成的 HMAC 签名 key，全类复用 */
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /** 工具类不允许实例化 */
    private JwtUtil() {
    }

    /**
     * 生成 token。
     *
     * @param userId 用户 id，会被写入 claim
     * @return 签名后的 JWT 字符串
     */
    public static String generate(Long userId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + EXPIRE_MILLIS);
        return Jwts.builder()
                // 用户 id 存成字符串，避免反序列化时数字类型（Integer/Long）不一致带来的麻烦
                .claim(CLAIM_USER_ID, String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(KEY)
                .compact();
    }

    /**
     * 验证 token 并返回用户 id。
     *
     * <p>会校验签名和是否过期；任何不通过（签名错、过期、格式非法）都抛
     * {@link BusinessException}（{@link ResultCode#UNAUTHORIZED}）。</p>
     *
     * @param token 客户端传来的 token
     * @return token 中携带的用户 id
     * @throws BusinessException 验证失败时抛出，状态码 401
     */
    public static Long verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.get(CLAIM_USER_ID, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不匹配、已过期、格式错误、token 为空等，统一当作「未登录或登录过期」
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    }

    /**
     * 仅解析用户 id，不校验是否过期（仅用于调试 / 排查，业务鉴权请用 {@link #verify(String)}）。
     *
     * @param token token 字符串
     * @return 用户 id；解析不出则返回 null
     */
    public static Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.get(CLAIM_USER_ID, String.class));
        } catch (ExpiredJwtException e) {
            // 已过期：签名其实是有效的，过期异常里仍能拿到 claims，照样取出 userId
            return Long.valueOf(e.getClaims().get(CLAIM_USER_ID, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
