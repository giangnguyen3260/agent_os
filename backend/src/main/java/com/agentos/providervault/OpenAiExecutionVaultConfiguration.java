package com.agentos.providervault;

import com.agentos.orchestration.application.OpenAiExecutionRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.execution.openai.enabled", havingValue = "true")
class OpenAiExecutionVaultConfiguration {
  @Bean
  OpenAiExecutionRuntime openAiExecutionRuntime(Environment env) {
    try {
      var configured = ProviderCredentialVaultRuntime.assemble(env);
      return new OpenAiExecutionRuntime(
          configured.credentials(), configured.runtime().connections());
    } catch (Exception failure) {
      throw new IllegalStateException("OPENAI_EXECUTION_CREDENTIALS_UNAVAILABLE");
    }
  }
}
