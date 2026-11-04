package com.agentos.providerpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
      @Value("${agentos.provider-list.enabled:false}") boolean listEnabled)
      throws Exception {
    http.securityMatcher(
            request ->
                request
                    .getRequestURI()
                    .substring(request.getContextPath().length())
                    .matches(
                        listEnabled
                            ? "/api/v1/organizations/[^/]+/provider-connections(?:/options)?"
                            : "/api/v1/organizations/[^/]+/provider-connections/options"))
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
}
