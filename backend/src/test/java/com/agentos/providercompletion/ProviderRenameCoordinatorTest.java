package com.agentos.providercompletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class ProviderRenameCoordinatorTest extends ProviderRenameFixture {
  private static final String DATABASE = "rename_" + UUID.randomUUID();

  @DynamicPropertySource
  static void h2(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.datasource.url",
        () ->
            "jdbc:h2:mem:"
                + DATABASE
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF"
                + " NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
  }

  @Test
  void enableChecksRevisionAndAdapterPolicyAndRefusesMissingEvidenceRuntime() {
    var coordinator = metadataCoordinator();
    assertThat(
            coordinator
                .enable(human, org, UUID.randomUUID(), new RevisionCommand(Operation.ENABLE, id, 1))
                .failure()
                .code())
        .isEqualTo(Code.NO_CHANGE);
    assertThat(
            coordinator
                .disable(
                    human, org, UUID.randomUUID(), new RevisionCommand(Operation.DISABLE, id, 1))
                .state())
        .isEqualTo(WriteState.COMMITTED);
    var disabled = configuration();
    var enable = new RevisionCommand(Operation.ENABLE, id, 2);
    jdbc.update("update provider_deployment_policy set openai_enabled=false where id=1");
    assertThat(
            coordinator
                .enable(human, org, UUID.randomUUID(), new RevisionCommand(Operation.ENABLE, id, 1))
                .failure()
                .code())
        .isEqualTo(Code.REVISION_CONFLICT);
    assertThat(coordinator.enable(human, org, UUID.randomUUID(), enable).failure().code())
        .isEqualTo(Code.PROVIDER_DISABLED);
    jdbc.update("update provider_deployment_policy set openai_enabled=true where id=1");
    assertThat(coordinator.enable(human, org, UUID.randomUUID(), enable).failure().code())
        .isEqualTo(Code.RECEIPT_UNAVAILABLE);
    assertThat(configuration()).isEqualTo(disabled);
    assertThat(count("provider_connection_events")).isEqualTo(2);
    assertThat(count("provider_command_receipts")).isEqualTo(2);
  }
}
