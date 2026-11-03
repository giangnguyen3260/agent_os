package com.agentos.providerpolicy;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public wire shape, independent of credential storage and publication records. */
record ProviderListResponse(
    UUID organizationId, int page, int pageSize, long total, List<Item> items) {
  private static final DateTimeFormatter CLOCK =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record Item(
      UUID organizationId,
      UUID id,
      String provider,
      String credentialMode,
      String name,
      Object options,
      String state,
      String revision,
      String credentialRevision,
      String credentialState,
      String credentialMask,
      String createdAt,
      String updatedAt) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record WorkspaceOptions(String workspaceId) {}

  static ProviderListResponse fromSnapshot(UUID organization, ProviderListSnapshot.Page page) {
    return new ProviderListResponse(
        organization,
        page.page(),
        ProviderListSnapshot.PAGE_SIZE,
        page.total(),
        page.rows().stream().map(row -> item(organization, row)).toList());
  }

  private static Item item(UUID organization, ProviderListSnapshot.Row row) {
    if (!canonical(organization)
        || !organization.equals(row.organization())
        || !canonical(row.id())
        || row.credentialRevision() < 1
        || row.revision() < row.credentialRevision()
        || !("ENABLED".equals(row.state()) || "DISABLED".equals(row.state()))
        || !validName(row.name())
        || row.provider() == null) {
      throw PolicyFailure.unavailable();
    }
    String mode =
        switch (row.provider()) {
          case "OPENAI" -> "OPENAI_API_KEY";
          case "ANTHROPIC" -> "ANTHROPIC_API_KEY";
          case "GOOGLE_GEMINI" -> "GEMINI_API_KEY";
          default -> throw PolicyFailure.unavailable();
        };
    boolean anthropic = row.provider().equals("ANTHROPIC");
    if (!mode.equals(row.mode())
        || (row.workspace() != null
            && (!anthropic || !row.workspace().matches("wrkspc_[A-Za-z0-9]{1,128}")))) {
      throw PolicyFailure.unavailable();
    }
    return new Item(
        organization,
        row.id(),
        row.provider(),
        mode,
        row.name(),
        anthropic ? new WorkspaceOptions(row.workspace()) : Map.of(),
        row.state(),
        Long.toString(row.revision()),
        Long.toString(row.credentialRevision()),
        row.credentialAvailable() ? "PRESENT" : "UNAVAILABLE",
        row.credentialAvailable() ? "••••••••" : null,
        time(row.createdAt()),
        time(row.updatedAt()));
  }

  private static boolean canonical(UUID id) {
    return id != null && id.variant() == 2 && id.version() >= 1 && id.version() <= 5;
  }

  private static boolean validName(String name) {
    if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) return false;
    if (space(name.codePointAt(0)) || space(name.codePointBefore(name.length()))) return false;
    return name.codePoints()
        .noneMatch(
            c ->
                Character.isISOControl(c)
                    || c == 0x2028
                    || c == 0x2029
                    || (c >= 0xd800 && c <= 0xdfff));
  }

  private static boolean space(int codePoint) {
    return Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint)
        || codePoint == 0xfeff;
  }

  private static String time(OffsetDateTime value) {
    if (value == null) throw PolicyFailure.unavailable();
    OffsetDateTime utc = value.withOffsetSameInstant(ZoneOffset.UTC);
    if (utc.getYear() < 1 || utc.getYear() > 9999 || utc.getNano() % 1000 != 0) {
      throw PolicyFailure.unavailable();
    }
    return CLOCK.format(utc);
  }
}
