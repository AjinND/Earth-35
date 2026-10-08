package world.earth35.protocol;

import java.util.Arrays;
import java.util.List;

/** Supported hosted providers and the public onboarding metadata for each one. */
enum HostedProvider {
    NVIDIA("nvidia", "NVIDIA API Catalog", "openai/gpt-oss-20b",
        "https://integrate.api.nvidia.com/v1/chat/completions", "https://build.nvidia.com/models",
        "Use the exact ID from a NVIDIA Build model page that offers an endpoint.",
        "Use an NVIDIA Build API Catalog key, not a container-registry key."),
    OPENROUTER("openrouter", "OpenRouter", "openai/gpt-4.1-mini",
        "https://openrouter.ai/api/v1/chat/completions", "https://openrouter.ai/settings/keys",
        "Copy a chat model ID from OpenRouter, including its provider prefix and any :free suffix.",
        "Use an OpenRouter API key with access to the selected model."),
    OPENAI("openai", "OpenAI", "gpt-4.1-mini",
        "https://api.openai.com/v1/chat/completions", "https://platform.openai.com/api-keys",
        "Use an OpenAI chat model ID available to your API project.",
        "Use an OpenAI API project key. A ChatGPT subscription does not include API billing."),
    ANTHROPIC("anthropic", "Anthropic", "claude-haiku-4-5",
        "https://api.anthropic.com/v1/messages", "https://platform.claude.com/settings/keys",
        "Use a Claude model ID available to your Anthropic API account.",
        "Use an Anthropic API key. A Claude subscription does not include API billing.");

    final String id, label, defaultModel, endpoint, keyUrl, modelHelp, keyHelp;
    HostedProvider(String id,String label,String defaultModel,String endpoint,String keyUrl,String modelHelp,String keyHelp) {
        this.id=id;this.label=label;this.defaultModel=defaultModel;this.endpoint=endpoint;
        this.keyUrl=keyUrl;this.modelHelp=modelHelp;this.keyHelp=keyHelp;
    }
    record Option(String id,String label,String defaultModel,String keyUrl,String modelHelp,String keyHelp) {}
    Option option() {return new Option(id,label,defaultModel,keyUrl,modelHelp,keyHelp);}
    static HostedProvider find(String id) {return Arrays.stream(values()).filter(p->p.id.equals(id)).findFirst().orElse(null);}
    static List<Option> options() {return Arrays.stream(values()).map(HostedProvider::option).toList();}
}
