---
title: Troubleshooting
---

[Start](./) · [Installing](installing) · [Chat bots](chat-bots) · [Releasing](releasing) · **Troubleshooting**

**`plugin [nlsearch] was built for Elasticsearch version 9.5.5 but version 9.5.4 is running`**
Install the zip for your version, or build one: `./gradlew bundle -PesVersion=9.5.4`.

**502, `could not get an answer from ollama/qwen2.5-coder:7b: ... Connection refused`**
Ollama is not running, or runs somewhere else. Start it (`ollama serve`) or set
`nlsearch.url`.

**502, `... model "qwen2.5-coder:7b" not found`**
`ollama pull qwen2.5-coder:7b`, or set `nlsearch.model` to one you have
(`ollama list`).

**502, `the model's answer is not valid JSON`**
Happens with very small models. Try `qwen2.5-coder:7b` or bigger, or another
provider. The raw answer is in the error so you can see what it did.

**400, `the model's plan for [update] has no "id"`**
The model wanted to update by id but had none. Rephrase ("set the price of
product 1 to 20") or let it use a query ("set the price of the blue mug to 20").

**It picked the wrong index**
Name the index in the prompt ("in products, ..."). With more than 20 indices
the prompt only carries names, so field based guessing stops working; say
which index you mean.

**A search came back empty and the answer says there are none**
That used to be the one failure with no symptom. Two checks now cover it: a
query naming a field the index does not have is refused before it runs, and a
search that returns nothing is retried once if a word it looked for in a text
field is really a value of a keyword field. If it still comes back empty, the
answer is probably just empty. `"response": "raw"` shows the query it ran.

**It picked the wrong field, or invented a value**
Your fields are probably codes rather than words: a `dept` of `FW` instead of a
`category` of `footwear`. Run `POST /_nl/analyze` once. It reads the mappings
and some real documents, works out what the codes mean, and stores that so
every later question starts from it. Ask it again afterwards.

**The analysis looks out of date**
It is rebuilt whenever the mapping changes or `nlsearch.analysis_ttl` (24 hours
by default) runs out. To force it now, `POST /_nl/analyze` again. Pass
`"session"` to keep the result to one conversation instead of sharing it.

**A question still goes wrong after analysing**
Small models sometimes pick a plausible-but-wrong field even with a good
briefing. "Out of stock" through a `status_flag` rather than a quantity is the
classic. A larger model gets it right; so does saying which field you mean.

**Slow answers**
The first call after a restart loads the model (10 to 30 seconds with Ollama).
After that a 7B model answers in 1 to 4 seconds on an Apple Silicon laptop.
Big mappings make it slower; `nlsearch.timeout` is 60s by default.

**The very first question is much slower than the rest**
It is paying for the analysis of your indices, which is one longer model call
and then never happens again. Four small indices took about 40 seconds on a
local 7B. Run `POST /_nl/analyze` at deploy time and nobody waits for it. The
analysis gets `nlsearch.timeout` per index rather than in total, so a slow model
on a large cluster does not fail halfway.

**`SLF4J(W): No SLF4J providers were found` in the Elasticsearch log**
Three lines, once, the first time the plugin talks to a model. langchain4j
logs through SLF4J and there is no logging backend inside the plugin. Harmless.

**Nothing in the logs**
The plugin only logs when it could not save history, could not read or write
an analysis, or could not send an error. Look at
the response, everything is in there: the model used, the plan, the result.

---

[Start](./) · [Installing](installing) · [Chat bots](chat-bots) · [Releasing](releasing) · **Troubleshooting**

[Repository](https://github.com/sheikmohammedsha/nlsearch) · [Releases](https://github.com/sheikmohammedsha/nlsearch/releases) · [Wiki](https://github.com/sheikmohammedsha/nlsearch/wiki) · [Report a problem](https://github.com/sheikmohammedsha/nlsearch/issues)
