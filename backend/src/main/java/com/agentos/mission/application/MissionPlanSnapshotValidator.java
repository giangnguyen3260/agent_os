package com.agentos.mission.application;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Validates immutable configured content before admission can consume it. */
public final class MissionPlanSnapshotValidator {
  private MissionPlanSnapshotValidator() {}

  public static void requireValid(MissionPlanReviewService.Review r) {
    try {
      if (r == null
          || r.organizationId() == null
          || r.teamId() == null
          || r.missionId() == null
          || !List.of("CREATED", "READY").contains(r.status())
          || !r.canReviewConfiguration()
          || r.executionAuthorized()
          || !r.blockers().isEmpty()
          || !r.fingerprint().matches("[0-9a-f]{64}")
          || r.taskCount() < 1
          || r.taskCount() > 200
          || r.edgeCount() < 0
          || r.edgeCount() > 1000
          || r.tasks().size() != r.taskCount()
          || r.edges().size() != r.edgeCount()
          || r.order().size() != r.taskCount()) throw new IllegalArgumentException();
      var ids = new HashSet<UUID>();
      long bytes = 0;
      for (var t : r.tasks()) {
        if (t.id() == null
            || !ids.add(t.id())
            || t.title() == null
            || t.title().isBlank()
            || t.title().length() > 255
            || !"DRAFT".equals(t.status())
            || t.ownerId() == null
            || !"ACTIVE".equals(t.ownerStatus())
            || t.description() == null
            || t.description().isBlank()
            || t.description().length() > 8000
            || t.acceptanceCriteria() == null
            || t.acceptanceCriteria().isBlank()
            || t.acceptanceCriteria().length() > 4000
            || !List.of("LOW", "NORMAL", "HIGH", "URGENT").contains(t.priority()))
          throw new IllegalArgumentException();
        for (String revision : List.of(t.editRevision(), t.ownerRevision(), t.dependencyRevision()))
          if (!revision.matches("0|[1-9][0-9]{0,9}")
              || Long.parseLong(revision) > Integer.MAX_VALUE) throw new IllegalArgumentException();
        for (String text : List.of(t.description(), t.acceptanceCriteria())) {
          if (!StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException();
          bytes += text.getBytes(StandardCharsets.UTF_8).length;
        }
      }
      if (bytes > 1048576) throw new IllegalArgumentException();
      var positions = new HashMap<UUID, Integer>();
      for (int i = 0; i < r.order().size(); i++)
        if (!ids.contains(r.order().get(i)) || positions.put(r.order().get(i), i) != null)
          throw new IllegalArgumentException();
      var edges = new HashSet<MissionPlanReviewService.Edge>();
      for (var e : r.edges())
        if (!edges.add(e)
            || !ids.contains(e.taskId())
            || !ids.contains(e.dependencyId())
            || positions.get(e.dependencyId()) >= positions.get(e.taskId()))
          throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new MissionPlanAcceptanceService.Conflict();
    }
  }
}
