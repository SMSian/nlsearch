---
title: Releasing and publishing
---

[Start](./) · [Installing](installing) · [Chat bots](chat-bots) · **Releasing** · [Troubleshooting](troubleshooting)

This is the hands-on guide for the [nlsearch](https://github.com/sheikmohammedsha/nlsearch)
repo: how the GitHub Actions build works, how a release gets onto the Releases
page, how to publish the docs on the wiki and as a website, and what "making it
live" means for this project. Everything here runs on GitHub's free tier.

The same text is on the repo's wiki (Home page) and on the documentation site,
so people can read it without cloning anything.

---

## 1. What a release is

A plugin only loads into the exact Elasticsearch version it was compiled
against. So every release is named

    <plugin version>-<elasticsearch version>      e.g.  0.31-9.5.5

and the tag that produces it is the same with a `v` in front:

    v0.31-9.5.5

The zip that ends up on the Releases page is `nlsearch-0.31-9.5.5.zip`.
When Elasticsearch 9.5.6 comes out you do not change any code, you push a tag
`v0.31-9.5.6` and a new release appears, built against 9.5.6.

## 2. The GitHub Actions build (CI)

Two workflow files live in `.github/workflows/`:

| file          | runs when                       | does |
|---------------|---------------------------------|------|
| `build.yml`   | every push on every branch, every PR | runs the tests and keeps the zip, the jar and the sources jar as an artifact for 90 days |
| `release.yml` | a tag starting with `v` is pushed | builds for the Elasticsearch version in the tag, runs the tests, creates the GitHub release and attaches the zip |

Both use the free `ubuntu-latest` runner, JDK 21 from `actions/setup-java`, and
`gradle/actions/setup-gradle` so the dependency cache is reused between runs.

Cost: GitHub Actions is free without limits for public repositories. For a
private repository the free plan includes 2,000 minutes a month; one build of
this plugin takes about 3 minutes.

Nothing has to be configured for this. The workflows use the `GITHUB_TOKEN`
that GitHub creates for every run; the release workflow asks for
`permissions: contents: write` so that token may create releases. There is no
secret to add.

### Turning it on the first time

1. Push the repo to GitHub (already done, `main` branch).
2. Open the repo on github.com, go to the **Actions** tab. If GitHub asks
   "Workflows aren't being run on this repository", click **I understand my
   workflows, go ahead and enable them**. Public repos usually have this on
   already.
3. Settings -> Actions -> General -> Workflow permissions: **Read and write
   permissions** must be selected (it is the default for new repos). The
   release job needs it to create releases.

### Getting a build of any commit

Every commit on every branch is built, so you never have to build one yourself
to try it. Actions tab -> pick the run -> the artifact is at the bottom of the
run page, named after the commit, holding:

```
nlsearch-0.31-9.5.5.zip        install this with elasticsearch-plugin
nlsearch-0.31.jar              the plugin classes
nlsearch-0.31-sources.jar      the source, for an IDE
```

The version is the one in `build.gradle` whichever branch it came from, the
same version a release builds, so a branch artifact and the published zip are
the same thing from different commits. They are kept for 90 days.

## 3. Making a release

From your machine, on the `main` branch, with everything committed:

```bash
git tag v0.31-9.5.5
git push origin v0.31-9.5.5
```

That is all. Within a few minutes the Releases page
(https://github.com/sheikmohammedsha/nlsearch/releases) shows "nlsearch 0.31 for
Elasticsearch 9.5.5" with `nlsearch-0.31-9.5.5.zip` attached and release
notes generated from the commits. Tags containing `alpha`, `beta` or `rc` are
marked as pre-releases automatically.

Anyone can then install the plugin straight from that URL:

```bash
bin/elasticsearch-plugin install https://github.com/sheikmohammedsha/nlsearch/releases/download/v0.31-9.5.5/nlsearch-0.31-9.5.5.zip
```

### Releasing for another Elasticsearch version

```bash
git tag v0.31-9.5.6
git push origin v0.31-9.5.6
```

The workflow takes the last part of the tag as the Elasticsearch version and
passes it to Gradle (`-PesVersion=9.5.6`). If that version does not compile
against the plugin, the release fails and the Actions log tells you why.

### Bumping the plugin version

The default plugin version is in `build.gradle` (`version = ... '0.31'`).
The release workflow overrides it from the tag (`-PpluginVersion=0.31`),
so a tag `v0.31-9.5.5` releases as 0.31 without editing anything. Update the
default in `build.gradle` too when you move on, so local builds match.

### If you would rather do it by hand

Build locally (`./gradlew bundle`), then on GitHub: Releases -> **Draft a new
release** -> choose or create the tag `v0.31-9.5.5` -> title
"nlsearch 0.31 for Elasticsearch 9.5.5" -> drag
`build/distributions/nlsearch-0.31-9.5.5.zip` into the assets box -> tick
"pre-release" -> Publish. A ready-built copy of that zip is next to this file in
your Downloads folder.

### Deleting a bad release

Releases page -> the release -> Delete. Then delete the tag:
`git push origin :refs/tags/v0.31-9.5.5` and `git tag -d v0.31-9.5.5`.
Push the tag again after fixing things.

## 4. The wiki

The wiki is a separate git repository next to the main one. GitHub only creates
it when the first page is made through the website, so:

1. Open https://github.com/sheikmohammedsha/nlsearch/wiki and click **Create the first
   page**. Call it `Home`, paste this document, save. (Ready-made pages are in
   `~/Downloads/nlsearch-wiki/`: `Home.md`, `Installing.md`,
   `Chat-bots-and-sessions.md`, `Troubleshooting.md`. Once the first page
   exists you can push that whole folder with git, see step 2.)
2. From then on it can be edited either on the website or with git:

   ```bash
   git clone git@github.com:sheikmohammedsha/nlsearch.wiki.git
   cd nlsearch.wiki
   # edit Home.md, add pages like Installing.md, Chat-bots.md ...
   git add . && git commit -m "update" && git push
   ```

   Every `.md` file becomes a page; the file name is the page title. Two names
   are special: `_Sidebar.md` is rendered beside every page and `_Footer.md`
   under every page, which is how the wiki gets navigation. Neither has an
   equivalent on the site, where the row of links at the top and bottom of each
   page does the same job.

Settings -> General -> Features -> **Wikis** has to be ticked (it is by
default) and, if you want only you to edit it, tick "Restrict editing to
collaborators only". Reading is public for a public repo.

The wiki pages are generated from `docs/` rather than written twice. The Jekyll
front matter is stripped, the site's navigation rows are swapped for wiki ones
because the link targets differ, and the sidebar and footer are added. Keeping
one source means the two cannot drift.

Two things to watch if you write that script yourself. Match the navigation rows
by their shape, a single line of entries separated by middle dots, not by the
first link in the row: the current page is bold rather than linked, so a pattern
anchored to a link strips the rows on every page except the one you are looking
at. And keep the rows plain markdown. A kramdown attribute such as `{: .nav }`
styles them nicely on the site and is printed as literal text by the wiki and by
GitHub's own file view.

Suggested pages: `Home` (this guide), `Installing`, `Settings and providers`,
`Chat bots and sessions`, `Troubleshooting`.

## 5. The documentation site (GitHub Pages)

The `docs/` folder in the repository is published as a website at
<https://sheikmohammedsha.github.io/nlsearch/>. It is plain Markdown rendered by Jekyll
with one of GitHub's built-in themes, set in `docs/_config.yml`.

To turn it on, once:

1. Repository **Settings** -> **Pages**.
2. Source: **Deploy from a branch**.
3. Branch: **main**, folder: **/docs**. Save.

GitHub builds the site and the URL appears on that page a minute later. After
that every push to `main` that touches `docs/` rebuilds it; the progress shows
up under the Actions tab as a "pages build and deployment" run.

The pages are:

| file | page |
|------|------|
| `docs/index.md` | the landing page |
| `docs/installing.md` | Elasticsearch, the plugin, a model |
| `docs/chat-bots.md` | the request loop and the response |
| `docs/releasing.md` | this document |
| `docs/troubleshooting.md` | what the errors mean |

Each file starts with a small front matter block (`---` / `title:` / `---`).
Without it Jekyll copies the file through raw and the theme is not applied.

To change the look, swap `theme:` in `docs/_config.yml` for another
[supported theme](https://pages.github.com/themes/), for example
`jekyll-theme-minimal` or `jekyll-theme-slate`.

## 6. The jar on GitHub Packages

Every tag also publishes the jar to GitHub Packages, the Maven repository
attached to this repository. The release workflow does it with the token GitHub
gives the run, so again there is no secret to add; the job just asks for the
`packages: write` permission.

What goes up, under `org.aeruto:nlsearch:<plugin>-<elasticsearch>`:

| file | what it is |
|---|---|
| `nlsearch-0.31-9.5.5.jar` | the plugin classes on their own |
| `nlsearch-0.31-9.5.5-sources.jar` | the source, so an IDE can step into it |
| `nlsearch-0.31-9.5.5-plugin.zip` | the installable bundle, the same one as on the Releases page |
| `nlsearch-0.31-9.5.5.pom` | the dependencies |

**To install the plugin you still want the zip**, either from the Releases page
or the `-plugin.zip` above. The bare jar is not installable on its own: it holds
no dependencies, no `plugin-descriptor.properties` and no entitlement policy.
The jar is there for reading the code and for building something on top of it.

Using it from another project, with Gradle:

```groovy
repositories {
    maven {
        url = uri('https://maven.pkg.github.com/sheikmohammedsha/nlsearch')
        credentials {
            username = findProperty('gpr.user')
            password = findProperty('gpr.key')
        }
    }
}

dependencies {
    compileOnly 'org.aeruto:nlsearch:0.31-9.5.5'
}
```

or Maven:

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/sheikmohammedsha/nlsearch</url>
</repository>

<dependency>
  <groupId>org.aeruto</groupId>
  <artifactId>nlsearch</artifactId>
  <version>0.31-9.5.5</version>
</dependency>
```

One wrinkle worth knowing: GitHub requires a token to **read** Maven packages,
even public ones. Anyone consuming it needs a personal access token with
`read:packages`, put in `~/.gradle/gradle.properties` as `gpr.user` and
`gpr.key`, or in `~/.m2/settings.xml`. That is a GitHub rule, not something this
project chose. The zip on the Releases page needs no token at all, which is why
the install instructions point there.

Publishing by hand needs a classic token with `write:packages`:

```bash
GITHUB_ACTOR=<you> GITHUB_TOKEN=<token> ./gradlew publish
```

Packages show up under the repository's **Packages** section on the right of
the code page, and at
<https://github.com/sheikmohammedsha/nlsearch/packages>. Deleting a published version is
done from there; a version cannot be overwritten, so a re-released tag needs
the old version deleted first if the contents changed.

## 7. Making it live, start to finish

In order:

1. **Code on GitHub**: `main` holds the plugin source, build files, README,
   Bruno collection and the two workflows. Nothing else; local Elasticsearch,
   Ollama, build output and IDE files are ignored by `.gitignore`.
2. **CI green**: the `build` workflow passes on `main` (Actions tab).
3. **Release**: push the tag `v0.31-9.5.5`; check the Releases page shows
   the zip.
4. **README points at it**: the install command in the README uses the release
   URL, so it works the moment the release exists.
5. **Wiki**: create the Home page with this guide; add more pages as needed.
6. **Pages**: Settings -> Pages -> main / docs, so the site goes live.
7. **Packages**: check the jar appeared under the repository's Packages.
8. **Tell people**: the repo description and topics (Settings -> General ->
   Topics: `elasticsearch`, `elasticsearch-plugin`, `langchain4j`, `ollama`,
   `natural-language`, `chatbot`) make it findable on GitHub search.

For someone to use it they need: Elasticsearch 9.5.5, the zip from the
release, and a model (Ollama with `qwen2.5-coder:7b` works out of the box, or
an API key for OpenAI, Anthropic or Gemini in `elasticsearch.yml`). The README
has the three commands.

## 8. Local build, for reference

```bash
git clone git@github.com:sheikmohammedsha/nlsearch.git
cd nlsearch
./gradlew test            # unit tests
./gradlew bundle          # build/distributions/nlsearch-0.31-9.5.5.zip
./gradlew bundle -PesVersion=9.5.4   # for another Elasticsearch version
```

Needs JDK 21 or newer on the machine that builds; the plugin itself runs on the
JDK bundled with Elasticsearch.

---

[Start](./) · [Installing](installing) · [Chat bots](chat-bots) · **Releasing** · [Troubleshooting](troubleshooting)

[Repository](https://github.com/sheikmohammedsha/nlsearch) · [Releases](https://github.com/sheikmohammedsha/nlsearch/releases) · [Wiki](https://github.com/sheikmohammedsha/nlsearch/wiki) · [Report a problem](https://github.com/sheikmohammedsha/nlsearch/issues)
