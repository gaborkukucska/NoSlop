# NoSlop FAQ

Straight answers to the questions people ask first. If something here is wrong or out of date, please open an issue.

## What is NoSlop, in a paragraph?

An Android app for peer-to-peer chat and social sharing over Tor: end-to-end encrypted direct messages and group chats, friends-only sharing, and public broadcasts from creators on the mesh. Alongside it is a built-in, **tracker-free clearnet aggregator** that pulls in content from the open web, with no algorithm choosing for you. The aggregator is a temporary bridge: it keeps your feed full until creators join the mesh, and the goal is to outgrow it. You can turn it off.

## Why does NoSlop have a clearnet aggregator at all?

A social network with nothing in it isn't much use, and creators haven't joined the mesh yet. The aggregator is a temporary bridge: it brings content in from the open web, without trackers or an algorithm, so the app is worth opening on day one. The plan is to invite creators to share on the mesh once the app has been tested and scrutinised, and to let the mesh grow until the aggregator is no longer needed. It can be switched off at any time, and liking, commenting or sharing clearnet content moves that conversation onto the mesh.

## Is it really serverless?

No server holds your identity (a keypair that lives on your phone), your messages, your follows or your feed choices. But things do sit between peers, and you should know what they are:

* **Tor relays** carry traffic between peers and, by default, your clearnet requests. They are run by volunteers and don't hold your identity, data or choices.
* **The Official Creator Node** is optional and untrusted. It exists so a fresh install has somewhere to discover creators. It is an ordinary NoSlop node.
* **A proxy Worker.** Search and metadata requests for YouTube, Reddit and Jamendo pass through an open-source Cloudflare Worker, because those sites block many Tor exits. Over Tor (the default) the Worker never sees your IP address; with *Route Clearnet via Tor* off, it does. Video and audio bytes don't go through it. See the [privacy policy](PRIVACY_POLICY.md).
* **GitHub**, for update checks, in the GitHub build only.
* **The sites you read**, when the aggregator is on.

## Why not Briar, Cwtch, SimpleX or Session?

If all you need is a private messenger, use one of them, or Signal. They are more mature and have had far more scrutiny than NoSlop. I won't compare their designs point by point here, because I would probably get details wrong: read their own documentation.

NoSlop is trying to be something broader than a messenger: a small, private social network. Chat and group chat, friends-only sharing, and public broadcasts from creators you follow, all peer-to-peer over Tor, in chronological order with no algorithm. Dedicated messengers are more mature and more scrutinised, so if messaging alone is what you need, use one. Nothing stops you running NoSlop next to one.

## Won't a small network just stall?

It might. That is the honest risk of any peer-to-peer network, and anonymity improves with more people. What makes NoSlop worth using anyway:

* The built-in aggregator gives you a full feed on day one, with no friends needed. It's the stopgap until creators join the mesh.
* Small, trusted circles are the design. You connect with people you know, by QR code.
* Creator broadcasts spread by gossip, up to six hops.
* The optional Official Creator Node helps new installs find creators.

If the mesh stays small, NoSlop still works as a tracker-free reader, but that isn't the goal: the goal is to outgrow the aggregator.

## Isn't it slow over Tor? What about battery?

Tor adds delay, and an embedded Tor daemon plus background playback use more battery than a plain feed app. What helps: the app warms up videos up to three slides ahead so the next one is usually already playing, and a stream that stalls recovers in place. You can turn off *Route Clearnet via Tor* for the aggregator, which is faster but lets the sites you read see your IP address. Mesh traffic stays on Tor.

## Isn't unaudited crypto dangerous?

Yes, treat it that way.

* NoSlop uses standard primitives from Bouncy Castle (Ed25519, X25519, ChaCha20-Poly1305, AES-GCM, SHA3-256, Argon2id and HKDF). There are no home-made ciphers.
* The protocol that combines them is NoSlop's own. It has had repeated AI-assisted code reviews (kept in `docs/archived/`) and a lot of testing across devices, but no human professional has ever reviewed it. Review by someone qualified is very welcome: see [SECURITY.md](../SECURITY.md).
* Known gaps are listed in the [README](../README.md) (Alpha Status) and in [PROJECT_STATUS.md](PROJECT_STATUS.md): no forward secrecy for DMs yet, a database that is only partly encrypted, and update checks that are verified but not authenticated.

So don't use NoSlop for anything where a mistake could put you, or someone else, in danger. Use a mature messenger for that. To lower the risk in everyday use, connect only with people you know, in person, and treat creators you discover as strangers.

## Is it safe for children?

NoSlop isn't built or vetted for children. There is no central moderation and nobody to report to: anyone can run a discoverable creator node and publish anything. If a child uses it, a trusted adult should set it up, keep the child's connections to people they know in real life, have them follow creators only through the secondary identity, and use the content and mesh filters in Settings. The clearnet aggregator can be turned off too.

## What are discoverable creator nodes, and why can they be dangerous?

A creator node publishes public broadcasts under a second, burnable identity. Anyone can run one, so what you find there is unmoderated and the person behind it could be anyone.

That is why NoSlop follows discovered creators with your **secondary identity**: a different key and onion address from your permanent one. Creators you follow never get your permanent key or onion address, and you can burn the secondary identity if something goes wrong. Don't use *Share Permanent Identity* with someone you don't know.

One trade-off to know about: by default the secondary identity shows the same name, picture and bio as your main profile, so a creator can see whatever you put there. It protects your keys and onion address, not your profile. If you want to stay anonymous to creators, keep identifying details out of your profile.

## Was NoSlop written by AI?

Largely, yes. It is written with heavy help from several leading LLMs. I can't afford to hire developers, and in my experience these models write better code than many I could hire if I could. For now I direct the work and test every release myself, on real devices. I've made it all open source in the hope that qualified developers will read it, check it and improve it.

Releases are tested to the best of my ability, which is why they are marked **alpha**. NoSlop stays alpha until it gets enough contributions from qualified developers.

## How can I help?

Read [CONTRIBUTING.md](../CONTRIBUTING.md). The most valuable help is review of the cryptography, the protocol and the Android code, followed by testing on more phones and translations.
