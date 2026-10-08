---
title: nlsearch
---

**Start** · [Installing](installing) · [Chat bots](chat-bots) · [Releasing](releasing) · [Troubleshooting](troubleshooting)

nlsearch is an Elasticsearch plugin that adds one endpoint, `_nl`. You send it a
sentence, it asks a language model to turn the sentence into the matching
Elasticsearch call, runs that call, and hands you back the result together with
what it did.

```
POST /_nl
{"prompt": "red shoes under 50"}
```

```json
{
  "model": "ollama/qwen2.5-coder:7b",
  "action": "search",
  "index": "products",
  "body": {"query": {"bool": {"filter": [{"term": {"category": "shoes"}}, {"range": {"price": {"lt": 50}}}]}}},
  "response": "Three shoes come in under 50: the Green Summer Sandal at 20.00, the Wool Hiking Sock at 14.00 and the Red Running Shoe at 49.90.",
  "result": {"took": 3, "hits": {"total": {"value": 3, "relation": "eq"}, "hits": ["..."]}}
}
```

Every answer carries both halves: `response`, a sentence a person can read, and
`result`, what Elasticsearch returned. Send `"response": "explain"` for the
sentence alone or `"raw"` for the data alone.

It searches, adds documents one at a time or in bulk, updates and deletes them
by id or by query, creates and deletes indices, changes mappings and lists what
is there. The model can be Ollama on your own machine, OpenAI, Anthropic,
Gemini, or anything with an OpenAI compatible API. Everything is picked in
`elasticsearch.yml`.

It is made for chat bots: a single endpoint, no index name in the url, and an
optional `session` so follow-ups like "now only the ones in stock" work.

## Start here

- [Installing](installing) — Elasticsearch, the plugin, and a model
- [Chat bots and sessions](chat-bots) — the request loop and what comes back
- [Releasing and publishing](releasing) — the CI, the Releases page, this site
- [Troubleshooting](troubleshooting) — what the errors mean

Source, releases and issues live in the
[repository](https://github.com/sheikmohammedsha/nlsearch). Its `api/` folder holds the
same 51 requests as a Postman collection and as a Bruno one, including a group
that walks through a conversation and the stored history.

## In one minute

With [Ollama](https://ollama.com) running and Elasticsearch 9.5.5 installed:

```bash
ollama pull qwen2.5-coder:7b
bin/elasticsearch-plugin install --batch https://github.com/sheikmohammedsha/nlsearch/releases/download/v0.2-9.5.5/nlsearch-0.2-9.5.5.zip
bin/elasticsearch
```

```bash
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' \
  -d '{"prompt": "create an index called products with a name, a category and a price"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' \
  -d '{"prompt": "add a product called Blue Mug in kitchen for 12.5"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' \
  -d '{"prompt": "how many products are there per category"}'
```

## What a request does

1. If a `session` was given, the earlier turns are read from the hidden
   `.nlsearch-history` index.
2. What each index means is read from `.nlsearch-analysis`, and worked out there
   and then if it is missing or out of date.
3. The mappings of your indices are fetched, with the values the keyword fields
   hold and the span of the numbers and dates. An index with no briefing yet
   also gets a sample document; one with a briefing does not need it.
4. The model is asked for a plan and answers with one JSON object.
5. The plan becomes a real Elasticsearch request and runs.
6. Unless you asked for `raw`, the result goes back to the model once more and
   comes out as a sentence.
7. The plan, the sentence and the result come back, and the turn is remembered
   along with how it went, so the model can correct itself next time.

If Elasticsearch rejects the plan the model gets one more try, with the root
cause of the failure stated immediately before the request. If the plugin itself
refused the plan, there is no second try, so a refused "delete everything" never
turns into a narrower delete.

If two different fields would each answer the words and nothing says which is
meant, the answer is `"action": "reply"` and a question back rather than a
guess. Chatty, rude or badly typed requests are not questioned, they are
stripped down to what was actually asked. A query against the wrong field does
not fail; it returns documents and a confident sentence about them, which is the
one kind of wrong answer nobody catches.

## Knowing what your data means

The hard part is not query syntax, it is knowing what a field means. A field
called `dept` holding `FW`, `AP` and `EQ` tells a model nothing, and it will
guess. Ask for the most expensive piece of clothing and you get a backpack.

So nlsearch looks at the data before answering anything. It pulls a couple of
real documents for every value of every small keyword field, and works out what
those values mean:

```
POST /_nl/analyze
```

```
inventory
A list of stock items.
dept: FW = footwear (shoes, boots, sandals), AP = apparel (clothing, jackets),
      EQ = equipment (packs, poles, stoves).
shoes, footwear, trainers -> dept FW
clothing, apparel, garments -> dept AP
out of stock, none left -> qty_on_hand 0
```

From then on "how much equipment do we stock" becomes `{"term": {"dept": "EQ"}}`
rather than a guess, and that briefing goes in front of every later question.

You never have to call it: the first question about an index triggers it, and
the result is kept, so only that first question pays. It is redone when the
mapping changes or after `nlsearch.analysis_ttl`, 24 hours by default. It writes
down what codes mean, not what the data contains, so an index whose values are
already plain English gets a one-line briefing saying there was nothing to
decode. An index with nothing in it is skipped and analysed once it has
documents. Pass a
`session` to keep a briefing for one conversation alone.

| | |
|---|---|
| `POST /_nl/analyze` | look at everything, or one `index`, and store the result |
| `POST /_nl/analyze?force=true` | redo it even if what is stored is fresh |
| `GET /_nl/analyze` | what is stored, without looking again |

## Being exact

Put something in quotes and it is taken literally, with its capitals and
spacing: `how many 'Red Running Shoe' are there`. Single quotes, double quotes
and backticks all mean the same thing.

Outside quotes you can be as loose as you like. Text searches go out with
`fuzziness`, so plurals and typos land on the right thing: "shoes" finds
"Shoe", and "ceramik mug" finds the Blue Ceramic Mug.

## Settings

| setting | default | notes |
|---|---|---|
| `nlsearch.provider` | `ollama` | `ollama`, `openai`, `anthropic` or `gemini` |
| `nlsearch.model` | `qwen2.5-coder:7b` | the model name as the provider knows it |
| `nlsearch.url` | provider default | with `openai` this can be any OpenAI compatible server |
| `nlsearch.api_key` | empty | not needed for local servers |
| `nlsearch.timeout` | `60s` | how long to wait for the model |
| `nlsearch.analysis_ttl` | `24h` | how long a briefing stays good for |

All of them can be changed while the cluster is running:

```
PUT _cluster/settings
{"persistent": {"nlsearch.provider": "anthropic", "nlsearch.model": "claude-sonnet-4-5", "nlsearch.api_key": "sk-ant-..."}}
```

---

**Start** · [Installing](installing) · [Chat bots](chat-bots) · [Releasing](releasing) · [Troubleshooting](troubleshooting)

[Repository](https://github.com/sheikmohammedsha/nlsearch) · [Releases](https://github.com/sheikmohammedsha/nlsearch/releases) · [Wiki](https://github.com/sheikmohammedsha/nlsearch/wiki) · [Report a problem](https://github.com/sheikmohammedsha/nlsearch/issues)
