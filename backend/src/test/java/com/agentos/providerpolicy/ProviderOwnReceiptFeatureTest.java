package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

class ProviderOwnReceiptFeatureTest {
  @Test
  void controllerRequiresAtLeastOnePublicCommand() {
    var datasource = new DriverManagerDataSource();
    var jdbc = new JdbcTemplate(datasource);
    var context =
        new ApplicationContextRunner()
            .withUserConfiguration(ProviderCreateReceiptController.class)
            .withBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(datasource))
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(
                ProviderReceiptService.class,
                () -> new ProviderReceiptService(jdbc, new ProviderConnectionService(jdbc)))
            .withBean(ObjectMapper.class, ObjectMapper::new);
    context.run(
        running -> {
          assertThat(running).hasNotFailed();
          assertThat(running).doesNotHaveBean(ProviderCreateReceiptController.class);
        });
    for (boolean create : new boolean[] {false, true}) {
      for (boolean renameHttp : new boolean[] {false, true}) {
        for (boolean disableHttp : new boolean[] {false, true}) {
          context
              .withPropertyValues(
                  "agentos.provider-create.enabled=" + create,
                  "agentos.provider-rename.http-enabled=" + renameHttp,
                  "agentos.provider-disable.http-enabled=" + disableHttp,
                  "agentos.provider-rename.enabled=true",
                  "agentos.provider-disable.enabled=true")
              .run(
                  running -> {
                    assertThat(running).hasNotFailed();
                    if (create || renameHttp || disableHttp)
                      assertThat(running).hasSingleBean(ProviderCreateReceiptController.class);
                    else assertThat(running).doesNotHaveBean(ProviderCreateReceiptController.class);
                  });
        }
      }
    }
  }

  @Test
  void receiptSecurityMatcherUsesPublicFeatureFlagsWithoutWideningOtherPaths() {
    String collection = "/api/v1/organizations/" + UUID.randomUUID() + "/provider-connections";
    for (boolean list : new boolean[] {false, true}) {
      for (boolean create : new boolean[] {false, true}) {
        for (boolean rename : new boolean[] {false, true}) {
          for (boolean disable : new boolean[] {false, true}) {
            var pattern = OptionsSecurity.paths(list, create, rename, disable);
            assertThat(pattern.matcher(collection + "/options").matches()).isTrue();
            assertThat(pattern.matcher(collection).matches()).isEqualTo(list);
            assertThat(pattern.matcher(collection + "/receipts/" + UUID.randomUUID()).matches())
                .isEqualTo(create || rename || disable);
            // Noncanonical keys still enter the guarded chain for its safe validation response.
            assertThat(pattern.matcher(collection + "/receipts/malformed").matches())
                .isEqualTo(create || rename || disable);
            for (String suffix :
                new String[] {
                  "/receipts/",
                  "/receipts/a/b",
                  "/receipts/a/",
                  "/other",
                  "/" + UUID.randomUUID() + "/rename",
                  "/" + UUID.randomUUID() + "/disable"
                }) assertThat(pattern.matcher(collection + suffix).matches()).isFalse();
          }
        }
      }
    }
  }
}
