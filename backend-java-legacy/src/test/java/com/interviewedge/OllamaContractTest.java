package com.interviewedge;

import static org.junit.jupiter.api.Assertions.*;

import com.interviewedge.common.Json;
import com.interviewedge.infrastructure.ai.OllamaAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OllamaContractTest {
  @Test
  void rejectsRepeatedQuestionIgnoringPunctuationSpacingAndCase() throws Exception {
    withResponse(
        "  COMPARE indexed access: in ArrayList and LinkedList! ",
        adapter -> assertThrows(IllegalStateException.class, () -> generate(adapter)));
  }

  @Test
  void acceptsDistinctFollowUp() throws Exception {
    withResponse(
        "Why does traversing nodes make indexed access linear?",
        adapter ->
            assertEquals(
                "Why does traversing nodes make indexed access linear?", generate(adapter)));
  }

  private String generate(OllamaAdapter adapter) {
    return adapter
        .followUp(
            "TECHNICAL",
            "collections",
            "Compare indexed access in ArrayList and LinkedList.",
            "LinkedList traverses nodes, so indexed access is linear.",
            "LinkedList indexed access requires node traversal.",
            1)
        .question();
  }

  private void withResponse(String question, java.util.function.Consumer<OllamaAdapter> check)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              Json.write(
                      Map.of(
                          "message",
                          Map.of("content", Json.write(Map.of("followUpQuestion", question)))))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      check.accept(new OllamaAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "test"));
    } finally {
      server.stop(0);
    }
  }
}
