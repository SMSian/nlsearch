---
title: Installing
---

[Start](./) · **Installing** · [Chat bots](chat-bots) · [Releasing](releasing) · [Troubleshooting](troubleshooting)

You need three things: Elasticsearch 9.5.5, the plugin zip for that exact
version, and a model to talk to.

## 1. Elasticsearch

Any 9.5.5 node works. For a quick local one:

```bash
curl -O https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-9.5.5-darwin-aarch64.tar.gz   # pick your platform
tar xzf elasticsearch-9.5.5-*.tar.gz
cd elasticsearch-9.5.5
```

For playing around on your own machine, turn security off in
`config/elasticsearch.yml` (never in production):

```yaml
discovery.type: single-node
xpack.security.enabled: false
```

## 2. The plugin

```bash
bin/elasticsearch-plugin install https://github.com/sheikmohammedsha/nlsearch/releases/download/v0.2-9.5.5/nlsearch-0.2-9.5.5.zip
```

It asks you to accept two entitlements (`outbound_network` and
`manage_threads`): the plugin has to call the model over HTTP, and the JDK's
HTTP client has its own threads. Add `--batch` to skip the question.

The version in the zip name has to match the node exactly. A zip for 9.5.5
refuses to install into 9.5.4. Pick the right one from the Releases page or
build it: `./gradlew bundle -PesVersion=9.5.4`.

## 3. A model

The default is Ollama on the same machine with `qwen2.5-coder:7b`:

```bash
brew install ollama        # or https://ollama.com/download
ollama serve
ollama pull qwen2.5-coder:7b
```

Anything else goes in `config/elasticsearch.yml`:

```yaml
nlsearch.provider: openai            # ollama, openai, anthropic, gemini
nlsearch.model: gpt-4o-mini
nlsearch.api_key: sk-...
# nlsearch.url: https://api.groq.com/openai/v1   # any OpenAI compatible server, with provider openai
# nlsearch.timeout: 60s
# nlsearch.analysis_ttl: 24h          # how long what it worked out about an index stays good for
```

Or without a restart:

```
PUT _cluster/settings
{"persistent": {"nlsearch.provider": "gemini", "nlsearch.model": "gemini-2.5-flash", "nlsearch.api_key": "..."}}
```

## 4. Start and try

```bash
bin/elasticsearch
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "which indices do I have"}'
```

`GET _cat/plugins` lists `nlsearch` when it loaded. If the node refuses to
start, the log (`logs/<cluster name>.log`) says why; the usual reason is a
version mismatch.

---

[Start](./) · **Installing** · [Chat bots](chat-bots) · [Releasing](releasing) · [Troubleshooting](troubleshooting)

[Repository](https://github.com/sheikmohammedsha/nlsearch) · [Releases](https://github.com/sheikmohammedsha/nlsearch/releases) · [Wiki](https://github.com/sheikmohammedsha/nlsearch/wiki) · [Report a problem](https://github.com/sheikmohammedsha/nlsearch/issues)
