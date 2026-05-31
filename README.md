# Konnekt

Konnekt is a Kotlin connectivity library for application integrations that commonly sit at the edge of a backend service: LLM providers, messaging providers, geocoding, and blob storage.

## Features

### Provider-Agnostic LLM Access

Konnekt exposes one request model for multiple LLM providers. Applications create an `LlmEndpoint` with a provider type, model, credentials, and optional base URL, then call `LlmService` for chat, structured output, streaming, embeddings, and batch work.

Supported providers include Anthropic, OpenAI, Azure OpenAI, Google Gemini, Grok, DeepSeek, Mistral, Cohere, AWS Bedrock, Together AI, Fireworks AI, Groq, Perplexity, OpenRouter, and VLLM.

```kotlin
val endpoint = LlmEndpoint(
    providerType = LlmServiceProviderType.ANTHROPIC,
    model = "claude-3-5-sonnet-latest",
    apiKey = System.getenv("ANTHROPIC_API_KEY"),
)

val answer = LlmService.chat(
    prompt = "Summarize the patient handoff in three bullets.",
    endpoint = endpoint,
)
```

### Conversations And Fallbacks

`Conversation` keeps message history, optional system instructions, model parameters, tool definitions, and fallback endpoints together. `LlmService` automatically skips endpoints on rate-limit cooldown and can fall back across providers unless the request opts into custom fallback behavior.

```kotlin
val conversation = Conversation(
    endpoint = primaryEndpoint,
    fallbackEndpoints = listOf(backupEndpoint),
    systemPrompt = "You write concise clinical operations summaries.",
    maxTokens = 500,
)

val response = conversation.say("What changed since the last update?")
```

### Streaming, Tool Calling, And Structured Output

The LLM module supports token streaming, reasoning-token callbacks where providers expose them, JSON-schema-backed structured responses, tool definitions, tool-choice controls, and automatic tool execution loops through `ToolHandler`.

```kotlin
val request = LlmRequest(
    messages = listOf(LlmMessage.userMessage("Return a JSON triage summary.")),
    endpoint = endpoint,
    responseSchema = summarySchema,
)

val json = LlmService.chat(request).contentAsJson()
```

### Multimodal Requests

Messages can include text, image, document, audio, tool-use, and tool-result content parts. URL and base64 sources are available for image, document, and audio inputs, with actual support depending on the selected provider and model.

```kotlin
val message = LlmMessage.userMessage(
    content = "Describe this image.",
    image = ImageSource.Url("https://example.com/image.png"),
)
```

### Embeddings And Batch Jobs

Embedding requests and batch messaging use the same endpoint abstraction as chat. Provider-specific implementations advertise support through the common `EmbeddingProvider` and batch-provider contracts.

```kotlin
val embeddings = LlmService.embed(
    request = EmbeddingRequest(
        input = listOf("left ventricular hypertrophy", "normal sinus rhythm"),
        model = "text-embedding-3-small",
    ),
    endpoint = openAiEndpoint,
)
```

### Email, SMS, And Team Messaging

Konnekt includes direct-send and queued-message flows for email, SMS, and team messaging. Email providers include SMTP, Twilio SendGrid, and ZeptoMail. SMS currently includes Twilio and a test sender. Team messaging includes Slack and TigerConnect.

The messaging model records recipients, provider configuration IDs, delivery state, attempts, optional receipt tracking details, retry timing, and queue metadata through Exposed-backed tables.

```kotlin
val message = EmailMessage(
    to = listOf(MessageRecipient("recipient@example.com")),
    from = MessageAddress("noreply@example.com", "Aideway"),
    subject = "Your report is ready",
    body = "The report is available.",
    contentType = EmailMessage.TEXT_CONTENT_TYPE,
)

EmailService.send(message, smtpConfiguration)
```

### Message Templates

The template renderer validates declared variables and renders subject, HTML body, and text body templates. It supports required variables, type checks, conditional blocks, list loops, number/date/boolean format specifiers, HTML escaping, and sensitivity-tag collection.

```kotlin
val rendered = TemplateRenderer.render(
    template = messageTemplate,
    variables = mapOf(
        "patientName" to "Jane Smith",
        "alerts" to listOf("New lab result", "Follow-up due"),
    ),
)
```

### Geocoding

`CoordinatesService` discovers active coordinate providers and exposes a single geocoding entry point. Current providers include Google and MapMaker, with provider activation controlled by each provider's configuration.

```kotlin
CoordinatesService.initialize(defaultProviderName = "google")
val coordinates = CoordinatesService.geocode(address)
```

### Blob Storage

`Krate` is the blob storage facade for putting, getting, streaming, deleting, checking, and listing objects. Included providers cover local filesystem storage, in-memory development storage, and S3-compatible object storage without requiring the AWS SDK.

```kotlin
Krate.initialize(S3KrateProvider)
Krate.putBytes(
    bucket = "documents",
    key = "reports/summary.txt",
    data = "Report contents".toByteArray(),
    contentType = "text/plain",
)
```

### Configuration Integration

Konnekt uses ACK-backed properties for runtime configuration where provider modules need environment-specific values. For example, `EndpointBuilder` can build LLM endpoints from `KONNEKT_*_API_KEY`, `KONNEKT_*_MODEL`, and `KONNEKT_*_BASE_URL` properties, while storage providers use properties such as `KRATE_FILE_SYSTEM_ROOT_DIR`, `KRATE_MEMORY_ENABLED`, and `KRATE_S3_*`.

## Documentation

- [LLM module](docs/llm.md)

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```
