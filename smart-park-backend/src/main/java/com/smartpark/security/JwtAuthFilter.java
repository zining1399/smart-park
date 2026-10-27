package com.smartpark.security;

import com.smartpark.common.ErrorCodes;
import com.smartpark.entity.SysUser;
import com.smartpark.service.SysUserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 过滤器：解析 Authorization: Bearer &lt;token&gt;，装载 SecurityContext。
 * 任何失败都不抛异常，按匿名请求放行，由 AuthenticationEntryPoint 统一回 40101。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SysUserService sysUserService;
    private final SecurityErrorHandlers errorHandlers;

    public JwtAuthFilter(JwtService jwtService, SysUserService sysUserService,
                         SecurityErrorHandlers errorHandlers) {
        this.jwtService = jwtService;
        this.sysUserService = sysUserService;
        this.errorHandlers = errorHandlers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Claims claims = jwtService.parse(token, JwtService.TYPE_ACCESS);
            if (claims != null) {
                String username = claims.getSubject();
                SysUser user = sysUserService.findEnabledByUsername(username);
                if (user == null) {
                    // 账号不存在或已被禁用：查不到启用态记录。区分两种情况给出语义，
                    // 由于过滤器每次请求都查库，禁用在下一个请求即生效（无需黑名单表 / Redis）
                    SysUser any = sysUserService.findByUsername(username);
                    if (any != null) {
                        SecurityContextHolder.clearContext();
                        errorHandlers.writeError(response, HttpStatus.UNAUTHORIZED,
                                ErrorCodes.ACCOUNT_DISABLED, "账号已被禁用，请联系管理员");
                        return;
                    }
                } else {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            username, null,
                            List.of(new SimpleGrantedAuthority(Roles.authorityOf(user.getRole()))));
                    auth.setDetails(user);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
