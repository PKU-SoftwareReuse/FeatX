package top.naccl.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import top.naccl.service.LoginLogService;
import top.naccl.service.impl.UserServiceImpl;

/**
 * @Description: Spring Security配置类
 * @Author: Naccl
 * @Date: 2020-07-19
 */
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
    UserServiceImpl userService;

    @Autowired
    LoginLogService loginLogService;

    @Autowired
    MyAuthenticationEntryPoint myAuthenticationEntryPoint;

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userService);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        //禁用 csrf 防御
        http.csrf().//开启跨域支持
        disable().cors().//基于Token，不创建会话
        and().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and().//放行获取网页标题后缀的请求
        authorizeRequests().antMatchers("/admin/webTitleSuffix").//任何 /admin 开头的路径下的请求都需要经过JWT验证
        permitAll().antMatchers(HttpMethod.GET, "/admin/**").hasAnyRole("admin", "visitor").antMatchers("/admin/**").hasRole(//其它路径全部放行
        "admin").anyRequest().permitAll().//自定义JWT过滤器
        and().addFilterBefore(new JwtLoginFilter("/admin/login", authenticationManager(), loginLogService), UsernamePasswordAuthenticationFilter.class).addFilterBefore(new JwtFilter(), //未登录时，返回json，在前端执行重定向
        UsernamePasswordAuthenticationFilter.class).exceptionHandling().authenticationEntryPoint(myAuthenticationEntryPoint);
    }

    @ltm.Generated()
    public SecurityConfig() {
    }
}
