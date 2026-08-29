import re

with open('app/UI/src/main/java/com/sherlock/ui/SherlockBackendClient.java', 'r') as f:
    content = f.read()

method = """
    public CompletableFuture<CaseDto> updateLlmConfigAsync(String caseId, LlmConfigDto config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String requestBody = objectMapper.writeValueAsString(config);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/cases/" + caseId + "/llm"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), CaseDto.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to update LLM config: " + e.getMessage(), e);
            }
        });
    }
}
"""

content = re.sub(r'}\s*$', method, content)

with open('app/UI/src/main/java/com/sherlock/ui/SherlockBackendClient.java', 'w') as f:
    f.write(content)

