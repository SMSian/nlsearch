# nlsearch

Talk to Elasticsearch in plain English.

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
  "body": {"query": {"bool": {"must": [{"match": {"name": {"query": "red shoes", "operator": "and", "fuzziness": "AUTO"}}}], "filter": [{"term": {"category": "shoes"}}, {"range": {"price": {"lt": 50}}}]}}},
  "result": {"took": 3, "hits": {"total": {"value": 2, "relation": "eq"}, "hits": ["..."]}}
}
```

It searches, adds documents (one or many), updates and deletes them by id or by
query, creates and deletes indices, changes mappings and lists what is there.
The model can be Ollama on your own machine, OpenAI, Anthropic, Gemini, or
anything with an OpenAI compatible API. Everything is picked in
`elasticsearch.yml`.

It is made for chat bots: a single endpoint, no index name in the url, and an
optional `session` so follow-ups like "now only the ones in stock" work.

**Documentation**, the same pages on the site and in the
[wiki](https://github.com/sheikmohammedsha/nlsearch/wiki):

| | |
|---|---|
| [Start](https://sheikmohammedsha.github.io/nlsearch/) | what it is, one request end to end, the settings |
| [Installing](https://sheikmohammedsha.github.io/nlsearch/installing) | Elasticsearch, the plugin, and a model |
| [Chat bots and sessions](https://sheikmohammedsha.github.io/nlsearch/chat-bots) | history, what comes back, keeping users safe |
| [Releasing and publishing](https://sheikmohammedsha.github.io/nlsearch/releasing) | the CI, the Releases page, this site |
| [Troubleshooting](https://sheikmohammedsha.github.io/nlsearch/troubleshooting) | what the errors mean |

## Install

You need Elasticsearch 9.5.5 (the plugin is tied to the exact version, see
[Releases](../../releases) for other versions) and a model to talk to. The
quickest model is [Ollama](https://ollama.com):

```
ollama pull qwen2.5-coder:7b
```

Then install the plugin and restart the node:

```
bin/elasticsearch-plugin install https://github.com/sheikmohammedsha/nlsearch/releases/download/v0.2-9.5.5/nlsearch-0.2-9.5.5.zip
```

It asks you to accept two entitlements, `outbound_network` and
`manage_threads`: the plugin talks to the model over HTTP, and the JDK's HTTP
client has its own threads. Add `--batch` to skip the question.

With Ollama running on the same machine nothing else is needed. For another
provider put a few lines in `config/elasticsearch.yml`:

```yaml
nlsearch.provider: openai          # ollama (default), openai, anthropic or gemini
nlsearch.model: gpt-4o-mini
nlsearch.api_key: sk-...
```

## Use it

```
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "create an index called products with a name, a category, a price and whether it is in stock"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "add a product called Blue Mug in category kitchen for 12.5, in stock"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "how many products are there per category"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "give everything in kitchen a 10 percent discount"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "remove all products that are out of stock"}'
curl "localhost:9200/_nl?q=which+indices+do+I+have"
```

The request body takes four things:

| field      | what it does |
|------------|--------------|
| `prompt`   | what you want, in your own words (`?q=` on a GET does the same) |
| `session`  | any string. Send the same one for a whole conversation and the model sees the earlier turns, so "them", "those" and "now also ..." make sense |
| `dry_run`  | `true` to only get the plan back, nothing is executed. Good before anything destructive |
| `response` | `both` (the default), `explain` or `raw`. See below |

Put something in quotes and it is taken literally: `how many 'Red Running Shoe'
are there`. Single quotes, double quotes and backticks all mean the same.
Outside quotes the wording is yours to mangle; text searches are fuzzy, so
plurals and typos still find the right thing.

## What comes back

Every answer carries two halves:

| field | what it is |
|-------|------------|
| `response` | a sentence a person can read, written by the model from what Elasticsearch returned |
| `result` | the Elasticsearch response itself |

```json
{
  "model": "ollama/qwen2.5-coder:7b",
  "action": "search",
  "index": "products",
  "body": {"size": 0, "query": {"term": {"in_stock": false}}},
  "response": "There are 5 products out of stock.",
  "result": {"took": 2, "hits": {"total": {"value": 5, "relation": "eq"}, "hits": []}}
}
```

A chat bot usually wants both: show `response` to the person, keep `result` for
the table underneath. The `response` field asks for less:

| `response` | you get |
|------------|---------|
| missing, or `both` | the sentence and the data |
| `explain` | the sentence only |
| `raw` | the data only, and no second call to the model, so it is the fastest |

Writing the sentence costs one extra model call, which is why `raw` exists.

Before those two the answer carries `model` (which model answered), `session`
if you sent one, and what the model decided: `action` and, depending on it,
`index`, `id`, `body` or `docs`. A dry run has `dry_run: true` and neither
half, since nothing ran. The HTTP status is the one Elasticsearch gave: 201 for
a new document, 404 for an index that does not exist, and so on.

`"action": "reply"` means nothing ran, and `text` says why. It happens in three
cases: the request is not about the data, something needed is genuinely missing
("delete it" with no session), or two different fields would each answer the
words and nothing says which is meant. In that last case it asks which you meant,
because a query against the wrong field does not fail: it returns documents and a
confident sentence about them. Mood, small talk, swearing and typos are stripped
and answered, never questioned.

Errors come back the way Elasticsearch always reports them, with a `reason`
written for a person: 400 when the plan was refused (a delete on `*`, a write
through GET), 502 when the model is unreachable or produced something unusable,
and whatever Elasticsearch said when it rejected the call itself.

A `GET /_nl?q=...` can only read. Anything that changes data has to be a POST.

## Knowing what your data means

The hard part is not the query syntax, it is knowing what a field means. A field
called `dept` holding `FW`, `AP` and `EQ` tells a model nothing, and it will
guess. Ask for the most expensive piece of clothing and you will get a backpack.

So nlsearch looks at the data first. It pulls a couple of real documents for
every value of every small keyword field, and works out what the values mean:

```
POST /_nl/analyze
```

```json
{
  "analysed": 1,
  "indices": {
    "inventory": "A list of stock items.\ndept: FW = footwear (shoes, boots, sandals), AP = apparel (clothing, jackets, jeans), EQ = equipment (packs, poles, stoves).\nshoes, footwear, trainers -> dept FW\nclothing, apparel, garments -> dept AP\nout of stock, none left -> qty_on_hand 0\n..."
  }
}
```

From then on "how much equipment do we stock" becomes `{"term": {"dept": "EQ"}}`
instead of a guess.

It writes down what the codes mean, not what the data contains: an index whose
values are already plain English gets a one-line briefing saying there was
nothing to decode, because every keyword value is sent with each request anyway.

You do not have to call it. The first question about an index triggers it, and
the result is kept in `.nlsearch-analysis`, so only that first question pays.
It is redone when the mapping changes, or after `nlsearch.analysis_ttl`
(24 hours by default). An index with nothing in it is skipped, since there is
nothing to read a meaning from, and analysed once it has documents.

| | |
|---|---|
| `POST /_nl/analyze` | look at everything, or one `index`, and store the result |
| `POST /_nl/analyze?force=true` | redo it even if what is stored is still fresh |
| `GET /_nl/analyze` | what is stored, without looking again |

Pass a `session` and the briefing is kept for that conversation alone, which is
how you correct it for one chat without changing what everyone else sees.

## Conversations

`session` is the whole of it. Any string will do: the chat id from Slack or
WhatsApp, a row id from your own database, a UUID kept in the browser. Send the
same one on every message of a conversation, leave it out for one-off requests.
There is nothing to open first and nothing to close afterwards.

```bash
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "show me shoes",            "session": "chat-123"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "only the ones in stock",   "session": "chat-123"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "cheapest first",           "session": "chat-123"}'
curl -XPOST localhost:9200/_nl -H 'Content-Type: application/json' -d '{"prompt": "just the top two",         "session": "chat-123"}'
```

Each turn keeps what came before, so "the ones", "those" and "now also ..."
resolve. Without a session the second request has nothing to refer back to.

The turns live in `.nlsearch-history`, a system index: one document per
session, holding the whole conversation. Each turn stores what was asked, the
JSON the model answered with, and how that went:

```json
{"prompt": "only the ones in stock",
 "answer": "{\"action\":\"search\",\"index\":\"products\",\"body\":{...}}",
 "outcome": "4 hits: [id 1] Red Running Shoe, [id 4] Green Summer Sandal, ..."}
```

That `outcome` is what makes it more than a transcript. After a search it
carries the ids of the first hits, which is how a later "make it 22" knows
which document you mean; after a failure it carries the error, so the model
corrects itself rather than repeating the mistake.

```bash
curl localhost:9200/.nlsearch-history/_doc/chat-123?pretty   # one conversation
curl -XDELETE localhost:9200/.nlsearch-history/_doc/chat-123 # forget it
```

Nothing is thrown away, so a conversation can be replayed or audited in full.
Only its tail, the last ten turns, is shown to the model, because that is all a
context window has room for.

Being a registered system index means Elasticsearch hides it from every
wildcard, keeps it out of `_cat/indices`, and refuses writes that do not come
from the plugin. `.nlsearch-analysis` is the same kind of index. Naming either
of them in a prompt is refused, and the refusal for the analysis one points at
`POST /_nl/analyze?force=true`, which is the supported way to have it rebuilt.
Nothing expires on its own, so delete by query on `updated` if you want
conversations to age out.

Two things to know when wiring up a bot: send the turns of one session one
after another, because a race can lose a turn, and start a new session when the
subject changes so the model stops dragging the old one along.
[The full write-up](https://sheikmohammedsha.github.io/nlsearch/chat-bots) has the rest.

## Try the API

The `api/` folder holds the same 51 requests as a
[Postman](https://www.postman.com) collection and as a
[Bruno](https://www.usebruno.com) one:

```
api/nlsearch.postman_collection.json   import this into Postman
api/                                   open this folder in Bruno
```

Both have two variables, `host` and `session`, and the same ten groups. The
"Chat bots: sessions and history" group is meant to be run top to bottom: four
turns of one conversation, the same question again without a session so you can
see the difference, then reading, listing, expiring and deleting the stored
history.

## Settings

| setting              | default            | notes |
|----------------------|--------------------|-------|
| `nlsearch.provider`  | `ollama`           | `ollama`, `openai`, `anthropic` or `gemini` |
| `nlsearch.model`     | `qwen2.5-coder:7b` | the model name as the provider knows it |
| `nlsearch.url`       | provider default   | `http://localhost:11434` for ollama. With `openai` this can be any OpenAI compatible server: Groq, Together, DeepSeek, OpenRouter, LM Studio, vLLM, or Ollama's own `http://localhost:11434/v1` |
| `nlsearch.api_key`   | empty              | not needed for local servers |
| `nlsearch.timeout`   | `60s`              | how long to wait for the model |
| `nlsearch.analysis_ttl` | `24h`           | how long what it worked out about an index stays good for |

All of them can be changed while the cluster is running, no restart:

```
PUT _cluster/settings
{"persistent": {"nlsearch.provider": "anthropic", "nlsearch.model": "claude-sonnet-4-5", "nlsearch.api_key": "sk-ant-..."}}
```

## How it works

One request goes through these steps, all in
[`NLRestHandler`](src/main/java/org/aeruto/nlsearch/NLRestHandler.java):

1. If a `session` was given, the earlier turns are read from the
   `.nlsearch-history` index (a system index, one document per session, holding
   the whole conversation).
2. What each index means is read from `.nlsearch-analysis`, and worked out first
   if it is missing or out of date.
3. The mappings of all indices are fetched, with the values the keyword fields
   hold and the span of the numbers and dates. An index with no briefing yet
   also gets a sample document; one with a briefing does not need it.
4. [`Planner`](src/main/java/org/aeruto/nlsearch/Planner.java) sends the rules
   in [`prompt.txt`](src/main/resources/prompt.txt), the history, the mappings
   and the request to the model, and parses the JSON it answers into a
   [`Plan`](src/main/java/org/aeruto/nlsearch/Plan.java).
5. [`Actions`](src/main/java/org/aeruto/nlsearch/Actions.java) turns the plan
   into the real Elasticsearch request (search, index, bulk, update, delete,
   update_by_query, delete_by_query, create_index, delete_index, get_mapping,
   put_mapping) and runs it through the node client.
6. The plan and the result go back to you. Unless you asked for `raw`, the
   result goes to the model once more to be turned into a sentence.
7. The turn is appended to the session's history together with how it went, so
   the model can fix itself next time. The answer waits for that write, so a
   bot that fires the next question immediately still sees this turn.

If Elasticsearch rejects the plan (a field that does not exist, a malformed
query) the model gets one more try, with the root cause of the failure stated
immediately before the request rather than buried above the mappings, before
you see a failure. It does not get a second try when the plugin itself refused the
plan, so a refused "delete everything" never turns into a narrower delete.

[`Models`](src/main/java/org/aeruto/nlsearch/Models.java) builds the
[langchain4j](https://docs.langchain4j.dev) chat model from the settings in
[`NLSettings`](src/main/java/org/aeruto/nlsearch/NLSettings.java).

Things worth knowing:

- Writes use `refresh=true`, so what you add is searchable right away.
- `delete_index`, `delete_by_query` and `update_by_query` only run against one
  named index, never `*` or `_all`, whatever the model says.
- The mappings and samples sent to the model are capped at about 12k
  characters: first the samples go, then the field lists. Past that the model
  only sees index names, so name the index in your prompt.
- Every rule in [`prompt.txt`](src/main/resources/prompt.txt) is written to say
  how to find an answer, never what the answer is. None of them names a field
  from any particular dataset, because a rule that does only helps a dataset
  with that field. That is the whole difference between 0.1 and 0.2.
- Settings go in `elasticsearch.yml` or in `PUT _cluster/settings`. The API key
  is hidden from `GET _cluster/settings` and `GET _nodes/settings` either way.
- Requests of one session should be sent one after another; two at the same
  time can lose a turn of history.
- The model reads field names and sample values from your indices. Treat that
  like any other prompt input: a document that contains instructions can try
  to steer it, which is one more reason for `dry_run` before destructive things.
- Model calls run on the node's `generic` thread pool and block for the length
  of the call. Tens of concurrent chats per node are fine, thousands are not.

## Build and test

Every commit on every branch is built by GitHub Actions, and the run's artifact
holds the installable zip, the plugin jar and a sources jar, so you can try any
commit without building it yourself. Locally:

JDK 21 or newer is needed (the plugin runs on the JDK that comes with
Elasticsearch).

```
./gradlew test        # unit tests
./gradlew bundle      # build/distributions/nlsearch-0.2-9.5.5.zip
```

Try it on a local node:

```
bin/elasticsearch-plugin install file:///path/to/nlsearch-0.2-9.5.5.zip
bin/elasticsearch
```

To build for another Elasticsearch version: `./gradlew bundle -PesVersion=9.5.4`.

## Releases

A plugin only loads into the exact Elasticsearch version it was built for, so
every release is named `<plugin version>-<elasticsearch version>`, like
`0.2-9.5.5`. Pushing a tag `v0.2-9.5.5` makes GitHub Actions build
it for that Elasticsearch version and attach the zip to the
[Releases](../../releases) page, and publishes the jar to
[GitHub Packages](../../packages) as
`org.aeruto:nlsearch:<plugin version>-<elasticsearch version>`, with a sources
jar beside it.

To install the plugin you want the zip. The jar holds no dependencies, no
plugin descriptor and no entitlement policy, so it is there for reading the
code and building on it, not for `elasticsearch-plugin install`.
[The step by step](https://sheikmohammedsha.github.io/nlsearch/releasing) is on the site,
and `docs/` is what that site is built from.

## License

[Apache 2.0](LICENSE)
