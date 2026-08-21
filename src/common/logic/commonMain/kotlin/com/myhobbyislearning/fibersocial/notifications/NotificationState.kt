package com.myhobbyislearning.fibersocial.notifications

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Persistent state of the notification system, updated by each sync cycle.
 *
 * @property knownEvents Event permalinks already seen in group scrapes, mapped to the
 *   epoch-millis they were last seen (refreshed by every sync that sees them; events
 *   unseen for the retention window are pruned). An event absent from this map is
 *   "new" and triggers a notification.
 * @property scheduledReminders Reminders currently scheduled with the platform's alarm
 *   system. Kept so a sync can compute which alarms to cancel or reschedule when an
 *   event's time changes or an RSVP is withdrawn.
 * @property knownTopics Per-topic activity for topics the user has posted in, keyed by
 *   topic id ([MyPostsNotificationPlanner]). Defaulted so state persisted before this
 *   field existed still deserializes.
 * @property knownMessages High-water mark of the private messages already accounted for
 *   ([MessageNotificationPlanner], issue #375). Null before the first messages sync — and
 *   set back to null when the kind is disabled — so a re-enable seeds silently. Defaulted
 *   so state persisted before this field existed still deserializes.
 * @property knownGroups Per-group forum-activity marks for the groups the user subscribed
 *   to ([GroupActivityNotificationPlanner], issue #510), keyed by group id. Defaulted so
 *   state persisted before this field existed still deserializes.
 */
@Serializable
data class NotificationState(
    val knownEvents: Map<String, Long> = emptyMap(),
    val scheduledReminders: List<ScheduledReminder> = emptyList(),
    val knownTopics: Map<Long, KnownTopicActivity> = emptyMap(),
    val knownMessages: KnownMessages? = null,
    val knownGroups: Map<Long, KnownGroupActivity> = emptyMap(),
)

/**
 * One reminder scheduled with the platform.
 *
 * @property eventPermalink Event the reminder is for.
 * @property eventTitle Title at scheduling time (used for the notification text).
 * @property fireAtEpochMs When the reminder fires.
 * @property kind Which of the reminder offsets this is.
 */
@Serializable
data class ScheduledReminder(
    val eventPermalink: String,
    val eventTitle: String,
    val fireAtEpochMs: Long,
    val kind: ReminderKind,
)

/**
 * The reminder offsets the feature supports. The [offset] is how long before the
 * event's start the reminder fires (serialization is by name; the offset is code-only).
 */
@Serializable
enum class ReminderKind(val offset: Duration) {
    DAY_BEFORE(1.days),
    SOON(15.minutes),
}

/**
 * How often the user wants the app to check for new events, expressed qualitatively
 * rather than as precise hours: iOS background refresh is opportunistic (the OS decides
 * cadence from usage patterns, not the app), so a precise-hours promise would be
 * untruthful there. Each platform maps a cadence to its own scheduling primitive
 * underneath — Android to a `WorkManager` periodic interval (see `EventSyncWorker`).
 */
@Serializable
enum class PollCadence {
    HOURLY,
    A_FEW_TIMES_A_DAY,
    ONCE_A_DAY;

    companion object {
        /** Buckets a legacy precise-hours setting into a qualitative cadence by threshold. */
        fun fromHours(hours: Int): PollCadence = when {
            hours <= 1 -> HOURLY
            hours < 12 -> A_FEW_TIMES_A_DAY
            else -> ONCE_A_DAY
        }
    }
}

/** Human label for a poll cadence choice. */
fun pollCadenceLabel(cadence: PollCadence): String = when (cadence) {
    PollCadence.HOURLY -> "Hourly"
    PollCadence.A_FEW_TIMES_A_DAY -> "A few times a day"
    PollCadence.ONCE_A_DAY -> "Once a day"
}

/**
 * User-configurable notification settings.
 *
 * @property pollCadence How often the background sync scrapes for new events and RSVP
 *   changes. Null before the first migration read (see [effectivePollCadence]).
 * @property eventRemindersEnabled Whether reminders fire for the user's RSVP'd events
 *   (T-24h, T-15m). Disabling cancels any pending reminders on the next sync.
 * @property newGroupEventsEnabled Whether "a new event was added to your group"
 *   notifications post.
 * @property topicRepliesEnabled Whether "new replies in a topic you posted in"
 *   notifications post (the My Posts activity leg).
 * @property newMessagesEnabled Whether "a new private message arrived" notifications post
 *   (the messages leg, issue #375). The Settings *row* for this toggle is issue #376; the
 *   field and its gating land here so the row is a pure-UI change.
 * @property groupActivityEnabled Whether "new posts in a group you subscribed to"
 *   notifications post (the group-activity leg, issue #510). Unlike the other four kinds
 *   this switch alone notifies about nothing: it is a master switch over a per-group
 *   opt-in ([SubscribedGroupsStore]), which starts empty. That is why it can default on
 *   like its siblings without making any existing install noisier — what it actually
 *   controls is whether the per-group subscribe control is offered at all.
 *
 * The five per-kind switches all default on and are defaulted fields, so JSON stored
 * before they existed (issues #335, #375, #510) deserializes with every kind enabled — the
 * pre-#335 behavior. They gate *what* to notify about; [pollCadence] stays orthogonal
 * (*how often* to check). See [EventSyncRunner] for how a disabled kind skips its scrape
 * and re-seeds silently on re-enable.
 */
@Serializable
data class NotificationSettings(
    val pollCadence: PollCadence? = null,
    val eventRemindersEnabled: Boolean = true,
    val newGroupEventsEnabled: Boolean = true,
    val topicRepliesEnabled: Boolean = true,
    val newMessagesEnabled: Boolean = true,
    val groupActivityEnabled: Boolean = true,
    // Legacy precise-hours setting, superseded by [pollCadence] (issue #113). Retained,
    // internal to this module, purely so a store holding pre-migration JSON migrates to
    // a qualitative bucket via [effectivePollCadence] instead of silently resetting to
    // the default the first time it's next loaded.
    internal val pollIntervalHours: Int = 6,
) {
    /** [pollCadence] if set; otherwise the legacy hours setting bucketed into a cadence. */
    val effectivePollCadence: PollCadence
        get() = pollCadence ?: PollCadence.fromHours(pollIntervalHours)

    companion object {
        val DEFAULT_POLL_CADENCE: PollCadence = PollCadence.A_FEW_TIMES_A_DAY
    }
}

/** Persistence for [NotificationState]; implemented per platform (like `TokenStorage`). */
interface NotificationStateStore {
    /** Returns the stored state, or null before the first completed sync. */
    suspend fun load(): NotificationState?

    suspend fun save(state: NotificationState)
}

/** Persistence for [NotificationSettings]; implemented per platform. */
interface NotificationSettingsStore {
    /** Returns the stored settings, or defaults when none were saved. */
    suspend fun load(): NotificationSettings

    suspend fun save(settings: NotificationSettings)
}

/**
 * Persistence for the set of topic ids the user has muted reply notifications for
 * (issue #338). Kept in its own store rather than inside [NotificationState] so the UI
 * can mutate it without a read-modify-write race against the sync's `NotificationState`
 * save — muting a topic must never clobber the sync's `knownTopics`/`knownEvents`.
 * Muting silences the *notification*, not the feed: a muted topic still advances its
 * [KnownTopicActivity] and still shows unread badges.
 */
interface MutedTopicsStore {
    /** Returns the muted topic ids, or an empty set when none were saved. */
    suspend fun load(): Set<Long>

    suspend fun save(mutedTopicIds: Set<Long>)

    /**
     * Atomically loads the current set, applies [transform], and saves the result if it
     * changed — the load-transform-save happens as one unit rather than as separate
     * [load]/[save] calls, so a concurrent mutator (the UI's mute toggle racing the
     * sync's retention pruning, or two rapid toggle taps racing each other) can't land
     * its own read-modify-write in between and get silently clobbered by the other's
     * stale write. Both mutation sites in this codebase ([EventSyncRunner]'s pruning and
     * the topic-detail mute toggle) go through this rather than raw [load]/[save].
     *
     * @return The set after applying [transform] (whether or not anything changed).
     */
    suspend fun mutate(transform: (Set<Long>) -> Set<Long>): Set<Long>

    companion object {
        /**
         * A store that mutes nothing and persists nothing — the null-object default for
         * an [EventSyncRunner] whose caller doesn't wire mute persistence (and for tests
         * that don't exercise muting). Real callers inject a [KeyValueMutedTopicsStore].
         */
        val EMPTY: MutedTopicsStore = object : MutedTopicsStore {
            override suspend fun load(): Set<Long> = emptySet()
            override suspend fun save(mutedTopicIds: Set<Long>) = Unit
            override suspend fun mutate(transform: (Set<Long>) -> Set<Long>): Set<Long> = transform(emptySet())
        }
    }
}

/**
 * Persistence for the set of group ids the user has subscribed to forum-activity
 * notifications for (issue #510) — the per-group opt-in that
 * [NotificationSettings.groupActivityEnabled] is the master switch over.
 *
 * **Opt-in, not opt-out**: an empty set is the default and means "notify about no group",
 * so turning the master switch on is by itself silent. This is the opposite default from
 * [MutedTopicsStore], which is an opt-*out* list — a busy public forum is a firehose in a
 * way a thread you personally posted in is not.
 *
 * Reactive, like [com.myhobbyislearning.fibersocial.moderation.BlockedUsersStore] and
 * unlike the plain load/save stores in this module: the feed's subscribe control renders
 * straight off [subscribedGroupIds], so a tap flips the icon without waiting on a disk
 * round-trip or a screen refresh. That means a single instance must be shared across the
 * authenticated app tree (constructed once via `remember` in `MainActivity` /
 * `MainViewController`); the background sync constructs its own and only reads it.
 *
 * Kept out of [NotificationState] for the same reason [MutedTopicsStore] is: the UI must
 * be able to mutate it without a read-modify-write race against the sync's state save.
 */
interface SubscribedGroupsStore {
    /** The subscribed group ids. Empty until [load] completes — call [load] once, early. */
    val subscribedGroupIds: StateFlow<Set<Long>>

    /** Loads the persisted set into [subscribedGroupIds]. Safe to call more than once. */
    suspend fun load()

    /** Adds or removes [groupId], publishing the change immediately, then persists. */
    suspend fun setSubscribed(groupId: Long, subscribed: Boolean)

    /**
     * Atomically drops every id outside [groupIds] — used by the sync to forget groups the
     * user has left, so a subscription set can't grow forever. Goes through the same lock
     * as [setSubscribed] so a subscribe landing mid-prune isn't clobbered by a stale
     * snapshot (see [MutedTopicsStore.mutate], whose reasoning this mirrors).
     */
    suspend fun retainAll(groupIds: Set<Long>)

    companion object {
        /**
         * A store that subscribes to nothing and persists nothing — the null-object
         * default for an [EventSyncRunner] whose caller doesn't wire subscriptions (and
         * for tests that don't exercise them). Real callers inject a
         * [KeyValueSubscribedGroupsStore].
         */
        val EMPTY: SubscribedGroupsStore = object : SubscribedGroupsStore {
            override val subscribedGroupIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
            override suspend fun load() = Unit
            override suspend fun setSubscribed(groupId: Long, subscribed: Boolean) = Unit
            override suspend fun retainAll(groupIds: Set<Long>) = Unit
        }
    }
}
