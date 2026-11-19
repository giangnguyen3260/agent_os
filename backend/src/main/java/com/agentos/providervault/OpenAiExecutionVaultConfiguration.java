package com.agentos.providervault;

import com.agentos.providercredentials.CredentialBoundary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.execution.openai.enabled", havingValue = "true")
class OpenAiExecutionVaultConfiguration {
  @Bean
  CredentialBoundary openAiExecutionCredentials(Environment env) {
    try {
      return ProviderCredentialVaultRuntime.assemble(env).credentials();
    } catch (Exception failure) {
      throw new IllegalStateException("OPENAI_EXECUTION_CREDENTIALS_UNAVAILABLE");
    }
  }
}
