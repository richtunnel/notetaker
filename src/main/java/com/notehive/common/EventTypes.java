package com.notehive.common;

/** Event types on the backbone. Additions only — never rename or reuse. */
public final class EventTypes {

    private EventTypes() {}

    public static final String NOTE_CREATED = "note.created";
    public static final String NOTE_UPDATED = "note.updated";
    public static final String NOTE_DELETED = "note.deleted";
    public static final String NOTE_RESTORED = "note.restored";
    public static final String COMMENT_CREATED = "comment.created";

    public static final String TEAM_CREATED = "team.created";
    public static final String TEAM_MEMBER_ADDED = "team.member_added";
    public static final String TEAM_MEMBER_REMOVED = "team.member_removed";

    public static final String USER_REGISTERED = "user.registered";
}
