package org.blu3fishez.springpaper.security;

import org.blu3fishez.springpaper.security.connection.config.PaperConnectionSecurityConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@Import({PaperConnectionSecurityConfiguration.class, PaperSecurityDefaults.class})
public class SecurityConfiguration {}
