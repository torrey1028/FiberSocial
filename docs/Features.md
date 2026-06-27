# Features
Prioritized list of features. 

## App Login
Users need some way to log into thier Ravelry account to access data.

### Requirements
* Can log into Ravelry.com
* Enable biometric login so that user doesn't have to type password everytime
* Never store password directly

### Opens
* What kind of authenication methods are supported by the Ravelry API? Can we store certs to open new login sessions without having to re-type passwords?

## Group Feed
Time sorted list of "things" going on in a single group. This should source data from the topics list, event posting, member activity, etc. and display in a single list. 

### Requirements
* Feed should be able to be ordered by posted time, high activity, other?
* Should be able to filter types of posts or individual posters out of the feed (local only)

### Opens
* Do events have a "posted" or "created" time? It would be good to have them appear in the feed when they are created as well as reminders. 

## Upcoming Events
We need a way to highlight upcoming events, including enabling configurable push reminders for users.

### Requirements
* Tab or feed exclusively for events, ordered date of event
* Ability to export calendar events to a Google/Apple calendar
* Calendar view?