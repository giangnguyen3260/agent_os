package com.agentos.providerpolicy;

import com.agentos.providerrename.ProviderRenameCoordinator;
import com.agentos.providertransport.ProviderRenameBody;
import com.agentos.providertransport.ProviderRenameRequest;
import com.agentos.providertransport.ProviderRenameResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Opt-in Rename ingress. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rename.http-enabled", havingValue = "true")
public class ProviderRenameSecurity {
  @Bean
  @Order(-2)
  SecurityFilterChain providerRenameChain(
      HttpSecurity http,
      PolicyAuthority authority,
      ProviderRenameCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    return ProviderMetadataSecurity.build(
        http,
        authority,
        json,
        "rename",
        ProviderRenameRequest::validate,
        (target, input) -> ProviderRenameBody.read(target, input).command(),
        coordinator::rename,
        ProviderRenameResponses::writeOutcome);
  }
}
