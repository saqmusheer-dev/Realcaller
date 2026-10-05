# SmartCaller

**SmartCaller — smartcaller.in** is an Android-first caller identification and spam protection platform.

## V1 architecture

- Native Android / Kotlin
- Local-first caller identification with Room/SQLite
- Call screening and spam protection
- Community reports and reputation scoring
- Business intelligence cards
- Google Places/Maps as the primary business-data provider
- OpenStreetMap as a provider/fallback map layer
- cPanel/PHP + MySQL cloud API (provider-neutral)
- Business-data caching so incoming-call decisions do not depend on a live Google request

## Product principle

**Incoming call → local database → immediate decision → optional cloud enrichment.**

The first release focuses on caller ID, spam protection, reputation, business identification, search, call history, and privacy controls. A full default dialer is intentionally out of scope for V1.

## Branding

- App name: **SmartCaller**
- Domain: **smartcaller.in**
- Launcher icon: SmartCaller shield + phone identity mark
- Repository: `saqmusheer-dev/Realcaller` (kept unchanged for V1 continuity)

## Planned modules

1. Caller ID engine
2. Spam Shield
3. Reputation Engine
4. Community Reports
5. Business Intelligence
6. Google Places/Maps integration
7. OSM fallback
8. Call History
9. Number Search
10. Block / Allow / Silence controls
11. Business verification
12. Local cache
13. Cloud API

## Status

V1 foundation — SmartCaller branding applied.
