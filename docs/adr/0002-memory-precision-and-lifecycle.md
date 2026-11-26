# ADR-0002: Deterministic memory promotion and bounded episodic memory

## Status

Accepted

Date: 2026-11-25

Implemented in 言语 1.6.1.

## Context

1.6.0 proved the local memory pipeline could persist summaries, source links and
candidate facts, but real-device screenshots exposed a quality failure:

- AI self-descriptions and capability limits were stored as user facts.
- One-off knowledge questions and memory tests became long-term experiences.
- A same-day mood became a permanent event.
- A city mention was treated as an ordinary stable fact.
- The summary repeated low-value details and long-term entries.
- Chinese questions were often retrieved as one long token, so relevant memories
  could be missed.

The cause was not only prompt wording. The app allowed the extraction model to
decide both extraction and promotion, and it had no deterministic read-time
filter for memories written by older rules.

## Decision

The model remains a candidate generator. The app owns four decisions:

1. **Discard before storage** when content is AI self-talk, a one-off query,
   a memory test, or has no user evidence.
2. **Promote to stable memory** only for explicit user preferences, boundaries,
   stable facts, relationship preferences, or a concrete plan with a future
   validity window.
3. **Keep as bounded recent event** for ordinary experiences. Events default to
   14 days and are capped at 30 days. They never auto-activate.
4. **Block contaminated legacy data at read time** until the user reviews or
   deletes it.

Sensitive facts, including exact city/region and location predicates, are always
candidates before confirmation and are excluded from the user-profile view.

A batch may produce an empty summary. An empty summary advances the message
checkpoint and injects nothing; it does not manufacture a narrative.

Retrieval does not use an "all active memories" fallback. A memory must match
direct text or the local FTS index. Chinese retrieval adds dependency-free
two- and three-character windows to avoid treating a full question as one token.

## Alternatives Considered

### Only tighten the model prompt

Rejected. Prompt changes reduce the error rate, but cannot prevent an updated
model, malformed field or old database row from contaminating every future turn.

### Let the model directly write and rewrite profile entries

Rejected. In a companionship product, a wrong personality label or sensitive
fact has a high relational cost. Reversible candidates and local policy are
more appropriate than autonomous profile rewriting.

### Store every event permanently

Rejected. Most events are contextual. Permanent event rows crowd retrieval and
turn temporary states into false personality evidence.

### Add embeddings immediately

Rejected for this version. The primary failure was precision and tokenization,
not lack of semantic similarity. Local exact/FTS retrieval with Chinese n-grams
is sufficient until a real recall benchmark fails.

## Consequences

### Positive

- The same screenshot regressions are covered by deterministic tests.
- Old contaminated rows stop entering new prompts even before user cleanup.
- Recent events are useful without becoming permanent facts.
- Sensitive location information stays visible to the user but out of the
  automatically injected profile.
- Empty/no-op batches are explicit and cheap.
- Chinese questions such as “你还记得杭州的面试吗” can retrieve “杭州面试”.

### Negative

- Some correctly extracted events and sensitive plans require one confirmation
  before they become durable context.
- Regex/token-based policy is conservative and may produce more candidates.
- The UI needs separate lists for stable, pending, recent and expired memory.

### Neutral

- No database schema migration is required for 1.6.1.
- The read-time policy remains compatible with existing 1.6.0 data.
- Event TTL is enforced by status maintenance and read-time filtering.

## Validation

- Screenshot-derived negative and positive fixtures.
- Real DeepSeek extraction checks for three transcript classes.
- JVM/Robolectric regression suite.
- Compose instrumentation tests on Android 15.
- Lint and APK build checks.
- v1.6.0 → v1.6.1 overwrite installation check.
