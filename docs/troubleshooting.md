---
title: Troubleshooting
---

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

**Slow answers**
The first call after a restart loads the model (10 to 30 seconds with Ollama).
After that a 7B model answers in 1 to 4 seconds on an Apple Silicon laptop.
Big mappings make it slower; `nlsearch.timeout` is 60s by default.

**`SLF4J(W): No SLF4J providers were found` in the Elasticsearch log**
Three lines, once, the first time the plugin talks to a model. langchain4j
logs through SLF4J and there is no logging backend inside the plugin. Harmless.

**Nothing in the logs**
The plugin only logs when it could not save history or send an error. Look at
the response, everything is in there: the model used, the plan, the result.


---

[Back to the start](./)
