---
title: Chat bots and sessions
---

[Start](./) · [Installing](installing) · **Chat bots** · [Releasing](releasing) · [Troubleshooting](troubleshooting)

nlsearch is one endpoint on purpose. A bot does not have to know index names or
query DSL; it forwards what the person typed and gets back what happened.

```
POST /_nl
{"prompt": "<what the user typed>", "session": "<one id per conversation>"}
```

## Keeping the conversation

`session` is any string you like: the chat id from Slack, Teams or WhatsApp, a
row id from your own database, a UUID you keep in the browser. Send the same
one on every message of a conversation and leave it out for one-off requests.

That one field is the whole of it. There is nothing to create first, nothing to
close afterwards, and no state to hold in your bot.

With a session the model sees the earlier turns, so these work:

| the user says | what happens |
|---|---|
| "red shoes under 50" | a search |
| "now only the ones in stock" | the same search plus a filter |
| "sort them by price" | the same search plus a sort |
| "just the top three" | the same search plus a size |
| "delete those" | a delete_by_query with that same query |
| "add two more like the first one" | a bulk, copying the shape of that document |

Without a session each request starts from nothing, so "only the ones in stock"
has no idea what "the ones" were.

## What is actually stored

One document per session in `.nlsearch-history`, holding the whole
conversation. Each turn is three strings:

```json
{
  "prompt":  "only the ones in stock",
  "answer":  "{\"action\":\"search\",\"index\":\"products\",\"body\":{...}}",
  "outcome": "4 hits: [id 1] Red Running Shoe, [id 4] Green Summer Sandal, ..."
}
```

On the next request those turns are replayed to the model as an ordinary chat:
your prompt, its own previous answer, then a line saying how that answer went.

`outcome` is the part that makes it more than transcript. After a search it
carries the ids and names of the first few hits, which is how a later "make it
22" knows which document you mean. After a failure it carries the error, so the
model can correct itself instead of repeating the mistake.

Every turn is kept, so a conversation can be replayed or audited in full. Only
the last ten are replayed to the model, because that is all a context window
has room for.

`.nlsearch-history` is a registered **system index**. Elasticsearch keeps it out
of every wildcard and out of `_cat/indices`, and refuses writes that do not come
from the plugin itself. The model is not allowed to name it either; a plan that
targets it is refused.

## Working with the history

```bash
# one conversation
curl -s localhost:9200/.nlsearch-history/_doc/chat-123?pretty

# just the turns
curl -s localhost:9200/.nlsearch-history/_doc/chat-123 | python3 -c '
import json, sys
for i, t in enumerate(json.load(sys.stdin)["_source"]["turns"], 1):
    print(i, t["prompt"])
    print("   ->", t["outcome"])
'

# which conversations exist
curl -s 'localhost:9200/.nlsearch-history/_search?size=50&_source=updated&pretty'

# forget one, or all of them
curl -s -XDELETE localhost:9200/.nlsearch-history/_doc/chat-123
curl -s -XDELETE localhost:9200/.nlsearch-history
```

Because it is an ordinary index you can treat it like one: snapshot it, add an
ILM policy to expire old conversations, or delete by query on `updated` to drop
anything older than a month.

```bash
curl -s -XPOST 'localhost:9200/.nlsearch-history/_delete_by_query' \
  -H 'Content-Type: application/json' \
  -d '{"query": {"range": {"updated": {"lt": "now-30d"}}}}'
```

Nothing expires on its own, so if you run many short-lived conversations, add
something like that on a schedule.

## Rules of thumb for a bot

- **One session per conversation, not per user.** If a person starts a new
  topic, give them a new session string and the model stops dragging the old
  one along.
- **Send the turns of a session one after another.** The history is read at the
  start of a request and written at the end, so two requests racing on the same
  session can lose a turn. Bots usually serialise per conversation anyway.
- **Everything is kept, ten turns are replayed.** The document holds the whole
  conversation; the model only sees the last ten. For a conversation that keeps
  circling one topic that is plenty; for one that wanders, start a new session.
  A very long conversation makes a large document that is rewritten on every
  turn, so start a new session rather than letting one run for thousands of
  messages.
- **History is best effort.** If the history index cannot be written the request
  still succeeds and the failure is logged. You never get an error because of
  history.
- **Nothing is shared between sessions.** Two sessions never see each other.
- **A follow-up stays on the index the conversation is already about.** "now
  only the ones in stock" narrows what you were just shown; it does not go
  looking for a better-fitting index. To move to another index, name it.
  Starting a new topic is what a new session is for.

## Showing the answer

This is the part that makes a bot easy to write. Every answer carries
`response`: a sentence the model wrote from what Elasticsearch returned, ready
to send straight to the person.

```json
{
  "action": "search",
  "response": "There are 5 products out of stock.",
  "result": { "hits": { "total": { "value": 5 } } }
}
```

So the simplest possible bot is: forward the message, print `response`. No
formatting of hits, no counting, no deciding whether a number or a list is the
answer. The model already did that.

`result` is the ordinary Elasticsearch response, there when you want to do more
than print a line: the hits are at `result.hits.hits`, a grouped question at
`result.aggregations`, a write at `result.result` (`created`, `updated`,
`deleted`). Show the sentence, draw a table from the data.

Ask for less when you want less:

| `response` | you get | when |
|---|---|---|
| missing, or `both` | the sentence and the data | the usual case for a bot |
| `explain` | the sentence only | the answer goes straight to a person |
| `raw` | the data only | your own code reads it, and you want the speed |

Writing the sentence is one more call to the model, so `raw` is the quickest of
the three. The top of the answer is unchanged in every mode: `action`, `index`,
`body` and the rest, which is good for a "here is what I did" line or a debug
view.

When `action` is `reply`, nothing ran and `text` is a sentence to show the user
as is: the request was not about the data, something was missing, or two
different fields would each have answered the words and it is asking which you
meant. Show
it and let them answer in the same session. A bot that treats `reply` as a
failure throws away the one safe move available when a question is ambiguous.

Errors come back as normal Elasticsearch errors with a `reason` written for a
person, so a bot can show it: 400 when the plan was refused, 404 for an index
that is not there, 502 when the model is unreachable or produced nonsense.

## Teaching it about your data

A bot is only as good as what the model knows about the indices behind it. Field
names like `dept`, `status_flag` and `vendor_cd` mean nothing on their own, and
a model asked to guess will guess confidently and wrongly.

`POST /_nl/analyze` looks at real documents and writes down what the fields and
their coded values mean. It happens automatically on the first question and is
then kept, so a bot does not have to do anything. It only decodes: an index
whose values are already words gets a one-line briefing saying there was nothing
to decode, because every keyword value is sent with each request anyway. Two
things are worth doing deliberately though:

- **Call it once at deploy time**, so the first person to ask a question does not
  pay for it.
- **Call it with a `session`** when a conversation reveals something the data
  does not say. The briefing is then kept for that conversation alone:

  ```bash
  curl -XPOST localhost:9200/_nl/analyze \
    -H 'Content-Type: application/json' \
    -d '{"index": "inventory", "session": "chat-123", "force": true}'
  ```

`GET /_nl/analyze` shows what it currently believes, which is the first place to
look when an answer comes back wrong: usually the briefing has a field's meaning
wrong, and that is visible in one read.

## Keeping users safe

- Send `"dry_run": true` first for anything that sounds destructive and show the
  plan before running it for real.
- `delete_index`, `delete_by_query` and `update_by_query` only ever run against
  one named index, never a pattern.
- A `GET /_nl` can only read. Use POST for anything that changes data.
- The plugin runs with the privileges of whoever calls `_nl`, so point the bot
  at a user with the right ones.
- Field names and sample values from your own documents go into the prompt, so
  a document containing instructions can try to steer the model. One more
  reason to dry run destructive things.
- Small local models are fine for one index with clear field names. For many
  indices, vague questions, or long conversations, a bigger model answers far
  better. Switching is one `PUT _cluster/settings`.

---

[Start](./) · [Installing](installing) · **Chat bots** · [Releasing](releasing) · [Troubleshooting](troubleshooting)

[Repository](https://github.com/sheikmohammedsha/nlsearch) · [Releases](https://github.com/sheikmohammedsha/nlsearch/releases) · [Wiki](https://github.com/sheikmohammedsha/nlsearch/wiki) · [Report a problem](https://github.com/sheikmohammedsha/nlsearch/issues)
