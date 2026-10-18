package com.agentos.model.infrastructure;

import com.agentos.model.application.ModelGateway;
import com.agentos.model.application.ModelRequest;
import com.agentos.model.application.ModelResponse;
import org.springframework.stereotype.Component;

@Component
public class StubModelGateway implements ModelGateway {

  @Override
  public ModelResponse execute(ModelRequest request) {
    return new ModelResponse("Model Gateway stub response", 0, 0);
  }
}
