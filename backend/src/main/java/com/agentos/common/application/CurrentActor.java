package com.agentos.common.application;

import com.agentos.common.domain.ActorType;
import java.util.UUID;

public record CurrentActor(ActorType actorType, UUID actorId) {

  public static CurrentActor system() {
    return new CurrentActor(ActorType.SYSTEM, null);
  }
}
