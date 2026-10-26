package com.agentos.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

class TraceSourceConstraintsTest {
  @Test
  void allServiceReadsUseReadOnlyRepeatableRead() {
    Transactional transaction = TraceExplorerService.class.getAnnotation(Transactional.class);
    assertThat(transaction.readOnly()).isTrue();
    assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    assertThat(
            Arrays.stream(TraceController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)))
        .hasSize(2);
  }

  @Test
  void productionSourceNeverReadsPrivateAuditColumnsOrWrites() throws Exception {
    Path directory = Path.of("src/main/java/com/agentos/trace");
    try (var paths = Files.walk(directory)) {
      for (Path file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        assertThat(source)
            .doesNotContain(
                "e.*",
                "e.payload",
                "e.actor_id",
                "getString(\"payload\")",
                "jdbc.update(",
                "jdbc.execute(",
                "PostMapping",
                "PutMapping",
                "DeleteMapping",
                "PatchMapping");
      }
    }
  }
}
