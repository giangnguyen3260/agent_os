package com.agentos.providerpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class OptionsSecurity {
  @Bean
  @Order(0)
  SecurityFilterChain providerOptionsChain(
      HttpSecurity http,
      PolicyAuthority authority,
      AdmissionService admission,
      ObjectMapper json,
      @Value("${agentos.provider-list.enabled:false}") boolean listEnabled,
      @Value("${agentos.provider-create.enabled:false}") boolean createEnabled,
      @Value("${agentos.provider-rename.http-enabled:false}") boolean renameEnabled,
      @Value("${agentos.provider-disable.http-enabled:false}") boolean disableEnabled,
      @Value("${agentos.provider-enable.http-enabled:false}") boolean enableEnabled,
      @Value("${agentos.provider-rotate.http-enabled:false}") boolean rotateEnabled,
      @Value("${agentos.provider-access-check.http-enabled:false}") boolean accessCheckEnabled)
      throws Exception {
    var paths =
        paths(
            listEnabled,
            createEnabled
                || renameEnabled
                || disableEnabled
                || enableEnabled
                || rotateEnabled
                || accessCheckEnabled,
            accessCheckEnabled);
    http.securityMatcher(
            request ->
                paths
                    .matcher(request.getRequestURI().substring(request.getContextPath().length()))
                    .matches())
        .securityContext(
            context ->
                context.securityContextRepository(new HttpSessionSecurityContextRepository()))
        .csrf(csrf -> csrf.disable())
        .requestCache(cache -> cache.disable())
        .addFilterAfter(
            new OptionsGate(authority, admission, json), SecurityContextHolderFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  static Pattern paths(boolean listEnabled, boolean receiptsEnabled) {
    return paths(listEnabled, receiptsEnabled, false);
  }

  static Pattern paths(boolean listEnabled, boolean receiptsEnabled, boolean accessCheckEnabled) {
    String suffix = listEnabled ? "(?:/options)?" : "/options";
    if (receiptsEnabled) suffix = "(?:" + suffix + "|/receipts/[^/]+)";
    if (accessCheckEnabled) suffix = "(?:" + suffix + "|/[^/]+/checks/[^/]+)";
    return Pattern.compile("/api/v1/organizations/[^/]+/provider-connections" + suffix);
  }
}
