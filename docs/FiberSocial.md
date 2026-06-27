# FiberSocial

## Overview
People in my crafting communities have expressed a desire to have an app instead of a website to facilitate event planning and online social interactions. Ravelry.com is largly agreed upon as the central place to interact but it lacks some features (such as mobile notifications) that can make it challening for using the platform for things like event planning. This app is meant to supplement the social aspects of Ravelry.com and make some of the existing features more accessible from a mobile device. 

## Goals
* Emphasize the social features of Ravelry in a mobile friendly format
* Facilitate community building and event planning by enabling things like mobile notifications, simplified UI centered on events, and private group social feeds.

## Non-Goals
* Replace Ravelry functionality
* Replace existing third party Ravelry apps
* Make money - this is a passion project intended to serve my local crafting community

## Research
### Ravelry.com
#### Existing Features
* Account Login
* Groups
  * Overview - group description, member list, event list, discussion threads
  * Some amount of moderator support, not sure exactly what powers they have, but will probably not be the first priority to enable
  * Forum - list of topics, each topic has posts, and each post has comments, likes, up/downvotes, saves, topic tags
  * Pages/Bundles - not entirely sure what gets put on these tabs, our group doesn't utilze these spaces
  * Members - list of everyone on the group
  * Activity - member project posts, other member activity
  * Neighbors - list of groups with users in common
  * Projects - member project posts explicity shared to group
  * Stash - stashes explicity shared to group

#### Ravelry Licencing Agreement
[License Link](https://www.ravelry.com/wiki/pages/Legal%20:%20Application%20Developer%20and%20API%20License%20Agreement)


### Other Third-Party Ravelry apps
Before starting my own app, it would be good to look at existing third-party apps and see if they already fill the stated goals of this project and if so, are they something I can contribute to and improve instead of starting my own app from scratch.

#### Stash2Go
App Focus:
* Full-featured Ravelry companion app for iOS and Android, focused on stash, project, and pattern management
* Includes row counters (with Apple Watch support), PDF management, offline access, and 26-language support
* Available since 2013; completely rewritten in 2025 after a quiet period (2020–2024)

Social Features:
* Friend activity feed: streams Ravelry friend activity (new projects, FOs, favorites, stash additions, forum posts)
* Forum access with per-topic push notifications, KAL detection, quick reply with quoting, and emoji support
* Direct messaging (read and reply to Ravelry personal messages)
* Trending forum topics card updated every 24 hours

App developer model:
* Indie solo developer; built and maintained by one person ("built by a crafter, for crafters")
* Freemium: core features free with ads; premium tier (subscription or one-time purchase) adds Smart Pattern Reader, unlimited row counters, offline mode, and removes ads
* Proprietary; not open source; not affiliated with Ravelry

#### Ravit
App Focus:
* Ravelry companion app for iOS and Android focused on fast, clean browsing of patterns and yarns, and managing projects, stash, and queue
* Offline access to projects and stash; data-efficient (claims up to 50% less data than browser); Dynamic Type support
* Photo upload, PDF download, and queue reordering supported

Social Features:
* Forum browsing and posting (browse and post to Ravelry forums from the app)
* Private messaging with push notifications; view user profiles and message them directly from project screens

App developer model:
* Small commercial indie studio: Enhancient Pty. Ltd., developed by a husband and wife team
* One-time purchase ($4.99); not a subscription model
* Actively maintained — last updated December 2025 (v2.2.0); not open source
* Developer maintains an active Ravelry group for user feedback

#### kntd:discover
App Focus:
* Ravelry companion app for iOS and Android, focused on pattern browsing, yarn discovery, and project/queue management
* Supports PDF attachment from device, photo upload with cropping, notes and tags on favorites, and bundle organization

Social Features:
* View and reply to Ravelry boards (forum topics accessible and postable from within the app)
* No friend feed, direct messaging, or event/group features noted

App developer model:
* Indie developer (Samuel Harrison); not open source; not affiliated with Ravelry
* Free with ads; optional ad-free subscription ($2.29/month or $6.49/year)
* Actively maintained — last updated June 2026 (v2.3.5)