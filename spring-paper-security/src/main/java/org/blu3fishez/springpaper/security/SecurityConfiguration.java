package org.blu3fishez.springpaper.security;

import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean public PaperSecurityInterceptor paperSecurityInterceptor(PaperConfiguration configuration) {
        return new PaperSecurityInterceptor(configuration.text("spring.paper.security.denied-message", "You do not have permission to do that."));
    }
}
